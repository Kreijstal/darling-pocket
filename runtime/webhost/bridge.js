/*
 * The page's side of the bridge between a web page and the iOS app showing
 * it in a UIWebView (Darling's WebKitLegacy, DWKBridge; protocol in
 * docs/darling-webhost.md of Darling). WebHost puts this first in each page
 * it loads, with __DWK_PAGE__ replaced by the page's id, and gives it
 * window.__dwkNative, which carries the messages.
 *
 * Calls nest both ways: while the page waits for the app, the app may call
 * into the page. Android runs no other script on a page's thread while it
 * waits in Java, so the waiting happens here: __dwkNative.request() and
 * .answer() return either the answer awaited or a request of the app's,
 * which this serves before waiting again. The app's requests that come while
 * the page is idle are served by drain(), which WebHost runs.
 *
 * JavaScript objects travel as handles ({"$js": n}) into the table here; the
 * app's Objective-C objects as handles ({"$oc": n}) that pages use through
 * Proxy objects.
 */
(function (page) {
	'use strict';
	var native = window.__dwkNative;
	if (!native || window.__dwk)
		return;
	var handles = [null], ids = new WeakMap(), proxies = new Map();

	function remember(value) {
		handles.push(value);
		return handles.length - 1;
	}

	function encode(value) {
		if (value === undefined)
			return { $u: 1 };
		if (value === null)
			return null;
		var type = typeof value;
		if (type === 'boolean' || type === 'string')
			return value;
		if (type === 'number')
			return isFinite(value) ? value : { $n: isNaN(value) ? 'nan' : value > 0 ? 'inf' : '-inf' };
		var objc = ids.get(value);
		if (objc !== undefined)
			return { $oc: objc };
		return { $js: remember(value) };
	}

	function decode(value) {
		if (value === null || typeof value !== 'object')
			return value;
		if (Array.isArray(value))
			return value.map(decode);
		if ('$u' in value)
			return undefined;
		if ('$m' in value)
			return { __dwk_m: 1 };
		if ('$n' in value)
			return value.$n === 'inf' ? Infinity : value.$n === '-inf' ? -Infinity : NaN;
		if ('$js' in value)
			return handles[value.$js];
		if ('$oc' in value)
			return proxy(value.$oc);
		if ('$a' in value)
			return decode(value.$a);
		return undefined;
	}

	function proxy(handle) {
		var found = proxies.get(handle);
		if (found)
			return found;
		found = new Proxy({}, {
			get: function (target, key) {
				if (typeof key !== 'string')
					return key === Symbol.toPrimitive ? function () { return '[object ObjC]'; } : undefined;
				if (key === 'toString' || key === 'valueOf')
					return function () { return '[object ObjC]'; };
				if (key === 'then' || key === 'toJSON' || key === 'constructor')
					return undefined;
				var result = call('get', handle, key);
				if (result !== null && typeof result === 'object' && result.__dwk_m === 1)
					return function () { return call('call', handle, key, Array.prototype.slice.call(arguments)); };
				return result;
			},
			set: function (target, key, value) {
				call('set', handle, key, value);
				return true;
			},
			has: function (target, key) {
				return typeof key === 'string' && call('get', handle, key) !== undefined;
			}
		});
		proxies.set(handle, found);
		ids.set(found, handle);
		return found;
	}

	/* Sends a request and returns its answer, serving the app meanwhile. */
	function send(request) {
		var message = JSON.parse(native.request(JSON.stringify(request)));
		while (message.serve)
			message = JSON.parse(native.answer(JSON.stringify(serve(message.serve))));
		var reply = message.reply;
		if (!reply)
			return undefined;
		if ('err' in reply) {
			console.warn('darling-pocket: the app failed a request: ' + reply.err);
			return undefined;
		}
		return decode(reply.ok);
	}

	/* An Objective-C object's property or method, for a proxy. */
	function call(op, handle, key, value) {
		var request = { op: op, obj: handle, key: key };
		if (op === 'set')
			request.value = encode(value);
		if (op === 'call')
			request.args = (value || []).map(encode);
		return send(request);
	}

	/* Answers one of the app's requests. */
	function serve(request) {
		var answer = { re: request.id };
		try {
			var object = request.obj !== undefined ? handles[request.obj] : undefined;
			var result;
			switch (request.op) {
			case 'eval':
				result = (0, eval)(request.script || '');
				break;
			case 'window':
				result = window;
				break;
			default:
				if (object === undefined || object === null)
					throw new Error('no such object');
				switch (request.op) {
				case 'get':
					result = object[request.key];
					break;
				case 'set':
					object[request.key] = decode(request.value);
					break;
				case 'remove':
					delete object[request.key];
					break;
				case 'index':
					result = object[request.i];
					break;
				case 'setindex':
					object[request.i] = decode(request.value);
					break;
				case 'string':
					result = String(object);
					break;
				case 'call':
					result = object[request.name].apply(object, decode(request.args) || []);
					break;
				case 'apply':
					result = object.apply(null, decode(request.args) || []);
					break;
				default:
					throw new Error('unknown request ' + request.op);
				}
			}
			answer.ok = encode(result);
		} catch (e) {
			answer.err = String(e && e.message || e);
		}
		return answer;
	}

	Object.defineProperty(window, '__dwk', { value: {
		/* The app's requests that came while the page was idle. */
		drain: function () {
			var request;
			while ((request = native.poll()) !== null && request !== undefined && request !== '')
				native.reply(JSON.stringify(serve(JSON.parse(request))));
		}
	} });

	/* The window object is new: the app sets its own objects on it (Cydia's
	   window.cydia) before the page's scripts run. */
	send({ op: 'clear', page: page, main: true, window: encode(window) });
})(__DWK_PAGE__);
