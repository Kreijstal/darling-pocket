/*
 * The phone's display for iOS apps: a Pocket server (Darling's
 * tools/pocket-host/pocket-server.c) whose screen is a Surface.
 *
 * A thread serves the apps. Each frame they present is composed straight into
 * the Surface's buffer. Touches, keys and input method text come from the UI
 * thread through PocketDisplay's native methods, and the apps' keyboard
 * requests go back to PocketDisplay.onKeyboard.
 */

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "pocket-server.h"

#define TAG "DarlingPocket"
#define log(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static JavaVM *vm;
static jclass display_class;
static jmethodID on_keyboard;

static struct pocket_server *server;
static pthread_t thread;
static int wake[2] = {-1, -1};

// The Surface, and its own lock: the UI thread attaches and detaches it.
// Only the server's thread draws, outside the server's lock, so the locks
// are always taken surface first.
static pthread_mutex_t surface_lock = PTHREAD_MUTEX_INITIALIZER;
static ANativeWindow *surface;
static int dirty;

// Asks the server's thread to draw ('d') or to stop ('q').
static void poke(char byte)
{
	if (wake[1] >= 0)
		write(wake[1], &byte, 1);
}

static void draw(void)
{
	pthread_mutex_lock(&surface_lock);
	if (surface != NULL && server != NULL) {
		struct pocket_screen screen = pocket_server_screen(server);
		ANativeWindow_Buffer buffer;
		if (ANativeWindow_lock(surface, &buffer, NULL) == 0) {
			if ((uint32_t) buffer.width == screen.width && (uint32_t) buffer.height == screen.height)
				pocket_server_compose(server, buffer.bits, (size_t) buffer.stride * 4, 1);
			ANativeWindow_unlockAndPost(surface);
		}
	}
	pthread_mutex_unlock(&surface_lock);
}

static void log_line(void *context, const char *line)
{
	(void) context;
	// Frames are too many to log.
	if (strncmp(line, "present ", 8) != 0)
		log("%s", line);
}

static void keyboard(void *context, int shown)
{
	(void) context;
	JNIEnv *env;
	int attached = 0;
	if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
		if ((*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK)
			return;
		attached = 1;
	}
	(*env)->CallStaticVoidMethod(env, display_class, on_keyboard, (jboolean) (shown != 0));
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionClear(env);
	if (attached)
		(*vm)->DetachCurrentThread(vm);
}

static void frame(void *context)
{
	(void) context;
	dirty = 1;
}

static void *serve(void *unused)
{
	(void) unused;
	for (;;) {
		int ready = pocket_server_poll(server, wake[0], -1);
		if (ready < 0) {
			log("the display stopped listening: %s", strerror(errno));
			break;
		}
		if (ready) {
			char byte;
			if (read(wake[0], &byte, 1) == 1) {
				if (byte == 'q')
					break;
				dirty = 1;
			}
		}
		if (dirty) {
			dirty = 0;
			draw();
		}
	}
	return NULL;
}

static struct pocket_screen make_screen(jint width, jint height, jint scale_milli, jint top, jint left, jint bottom,
                                        jint right)
{
	struct pocket_screen screen = {(uint32_t) width, (uint32_t) height, (uint32_t) scale_milli,
	                               (uint32_t) top, (uint32_t) left, (uint32_t) bottom, (uint32_t) right};
	return screen;
}

JNIEXPORT jint JNI_OnLoad(JavaVM *java, void *reserved)
{
	(void) reserved;
	vm = java;
	JNIEnv *env;
	if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK)
		return JNI_ERR;
	jclass found = (*env)->FindClass(env, "dev/kreijstal/darlingpocket/PocketDisplay");
	if (found == NULL)
		return JNI_ERR;
	display_class = (*env)->NewGlobalRef(env, found);
	on_keyboard = (*env)->GetStaticMethodID(env, display_class, "onKeyboard", "(Z)V");
	if (on_keyboard == NULL)
		return JNI_ERR;
	return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL Java_dev_kreijstal_darlingpocket_PocketDisplay_start(JNIEnv *env, jclass cls, jstring path,
                                                                                 jint width, jint height,
                                                                                 jint scale_milli, jint top, jint left,
                                                                                 jint bottom, jint right)
{
	(void) cls;
	if (server != NULL)
		return JNI_TRUE;
	const char *socket_path = (*env)->GetStringUTFChars(env, path, NULL);
	struct pocket_screen screen = make_screen(width, height, scale_milli, top, left, bottom, right);
	struct pocket_server_events events = {.log = log_line, .keyboard = keyboard, .frame = frame};
	server = pocket_server_listen(socket_path, &screen, &events);
	if (server == NULL)
		log("cannot listen on %s: %s", socket_path, strerror(errno));
	else
		log("listening on %s, %dx%d", socket_path, width, height);
	(*env)->ReleaseStringUTFChars(env, path, socket_path);
	if (server == NULL)
		return JNI_FALSE;
	if (pipe(wake) != 0 || pthread_create(&thread, NULL, serve, NULL) != 0) {
		pocket_server_close(server);
		server = NULL;
		return JNI_FALSE;
	}
	return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_dev_kreijstal_darlingpocket_PocketDisplay_stop(JNIEnv *env, jclass cls)
{
	(void) env;
	(void) cls;
	if (server == NULL)
		return;
	poke('q');
	pthread_join(thread, NULL);
	close(wake[0]);
	close(wake[1]);
	wake[0] = wake[1] = -1;
	pthread_mutex_lock(&surface_lock);
	pocket_server_close(server);
	server = NULL;
	pthread_mutex_unlock(&surface_lock);
}

JNIEXPORT void JNICALL Java_dev_kreijstal_darlingpocket_PocketDisplay_setScreen(JNIEnv *env, jclass cls, jint width,
                                                                                 jint height, jint scale_milli,
                                                                                 jint top, jint left, jint bottom,
                                                                                 jint right)
{
	(void) env;
	(void) cls;
	if (server == NULL)
		return;
	struct pocket_screen screen = make_screen(width, height, scale_milli, top, left, bottom, right);
	pocket_server_set_screen(server, &screen);
	pthread_mutex_lock(&surface_lock);
	if (surface != NULL)
		ANativeWindow_setBuffersGeometry(surface, width, height, AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM);
	pthread_mutex_unlock(&surface_lock);
	poke('d');
}

JNIEXPORT void JNICALL Java_dev_kreijstal_darlingpocket_PocketDisplay_setSurface(JNIEnv *env, jclass cls,
                                                                                  jobject java_surface)
{
	(void) cls;
	int have_screen = server != NULL;
	struct pocket_screen screen;
	if (have_screen)
		screen = pocket_server_screen(server);
	pthread_mutex_lock(&surface_lock);
	if (surface != NULL)
		ANativeWindow_release(surface);
	surface = java_surface ? ANativeWindow_fromSurface(env, java_surface) : NULL;
	if (surface != NULL && have_screen)
		ANativeWindow_setBuffersGeometry(surface, (int32_t) screen.width, (int32_t) screen.height,
		                                 AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM);
	pthread_mutex_unlock(&surface_lock);
	poke('d');
}

JNIEXPORT void JNICALL Java_dev_kreijstal_darlingpocket_PocketDisplay_touch(JNIEnv *env, jclass cls, jint phase,
                                                                             jint finger, jfloat x, jfloat y)
{
	(void) env;
	(void) cls;
	if (server != NULL)
		pocket_server_touch(server, (uint32_t) phase, (uint32_t) finger, x, y, pocket_server_now());
}

JNIEXPORT void JNICALL Java_dev_kreijstal_darlingpocket_PocketDisplay_key(JNIEnv *env, jclass cls, jboolean down,
                                                                           jint keycode, jint modifiers,
                                                                           jbyteArray characters)
{
	(void) cls;
	if (server == NULL)
		return;
	jsize length = characters ? (*env)->GetArrayLength(env, characters) : 0;
	jbyte *bytes = characters ? (*env)->GetByteArrayElements(env, characters, NULL) : NULL;
	pocket_server_key(server, down, (uint32_t) keycode, (uint32_t) modifiers, (const char *) bytes, (size_t) length);
	if (bytes)
		(*env)->ReleaseByteArrayElements(env, characters, bytes, JNI_ABORT);
}

// UTF-8, from Java: JNI's own strings are modified UTF-8.
JNIEXPORT void JNICALL Java_dev_kreijstal_darlingpocket_PocketDisplay_text(JNIEnv *env, jclass cls, jbyteArray text)
{
	(void) cls;
	if (server == NULL || text == NULL)
		return;
	jsize length = (*env)->GetArrayLength(env, text);
	jbyte *bytes = (*env)->GetByteArrayElements(env, text, NULL);
	pocket_server_text(server, (const char *) bytes, (size_t) length);
	(*env)->ReleaseByteArrayElements(env, text, bytes, JNI_ABORT);
}
