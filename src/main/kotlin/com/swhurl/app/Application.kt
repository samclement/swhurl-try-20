package com.swhurl.app

import io.micronaut.runtime.Micronaut

// An HTTP service on port 8080 (HttpController.kt); the platform probes GET /healthz.
fun main(args: Array<String>) {
    Micronaut.build(*args).banner(false).start()
}
