package com.stapk.mobile.nativeadapter

fun interface NativeRouteHandler {
    fun handle(request: NativeRequest): HttpResponse
}

class NativeRouter {
    private val routes = mutableMapOf<RouteKey, NativeRouteHandler>()

    fun post(path: String, handler: NativeRouteHandler) = register("POST", path, handler)

    fun get(path: String, handler: NativeRouteHandler) = register("GET", path, handler)

    fun dispatch(request: NativeRequest): HttpResponse? =
        routes[RouteKey(request.method.uppercase(), request.path)]?.handle(request)

    private fun register(method: String, path: String, handler: NativeRouteHandler) {
        require(path.startsWith('/')) { "Route path must start with /" }
        val key = RouteKey(method, path)
        require(routes.putIfAbsent(key, handler) == null) { "Duplicate route: $method $path" }
    }

    private data class RouteKey(val method: String, val path: String)
}
