# swhurl-try-20

Seed image and auto-merge timing test (throwaway)

A Kotlin ([Micronaut](https://micronaut.io/)) app on the [swhurl platform](https://github.com/samclement/swhurl-platform), made from the [swhurl Kotlin template](https://github.com/samclement/swhurl-app-template-kotlin). Every push to `main` is checked, published to `ghcr.io/<owner>/swhurl-try-20` and deployed to staging; production changes through **Promote to production**. What the image must provide, the checks and the dependency updates: the [template's guide](https://github.com/samclement/swhurl-app-template-kotlin#readme). What the app needs from the platform: [`swhurl.yaml`](swhurl.yaml).

An HTTP service: `HttpController.kt` answers on port 8080; the platform probes `GET /healthz`.

## Callers and calling other apps

`Platform.kt` holds what the platform gives the app ([calling another app](https://github.com/samclement/swhurl-platform/blob/main/docs/apps.md#calling-another-app)):

- **Who is calling.** `CallerFilter` (in `HttpController.kt`) runs before every route except `/healthz` and puts a `Caller` on the request: type `app` with the app's name (it sent a token the platform issued for this app alone), `user` with a signed-in person's email, or `anonymous`. A bad token is answered 401. Every request's log line and span carry `caller.type` and `caller.id`. For a route only apps may use, call `platform.requireApp(caller)`.
- **Secrets.** List each secret variable the app reads under `secrets:` in `swhurl.yaml` and read it with `platform.secret("API_TOKEN")`. Its value is set on the platform, never here: `make app-secret APP=<app> ENV=<env> NAME=API_TOKEN` in the platform checkout. A name pushed before its value deploys all the same; `secret()` then fails with a message naming that command, and the app page shows it as value missing.
- **Calling another app.** A call is granted on the platform, not in this repository: tick `orders` under **Connections** when creating the app, or later use the app page's **Connect to an app** or `make app-calls`. Then `platform.call("orders", "/path")` reaches the same environment's `orders` with this app's token for it; the callee's spans join the same trace. `GET /call/orders` in `HttpController.kt` is an example to replace.

## Reaching outside the cluster

A request to a host outside the cluster goes through the platform's egress proxy, which knows this app by the port it uses ([reaching outside the cluster](https://github.com/samclement/swhurl-platform/blob/main/docs/apps.md#reaching-outside-the-cluster)). Which hosts the app may reach is a grant on the platform, per environment, not in this repository: the app page's **Reach a host outside**, or `make app-egress APP=swhurl-try-20 ENV=staging ARGS="--add api.example.com"` (port 443 unless named; `*.example.com` is every name under it). Production is granted on its own, after promotion: a promotion never widens it.

- **Today nothing is refused.** The proxy logs every request with whether a grant covers it: in ClickStack, service `egress`, a `granted=-` line with this app's name is a host to grant before the platform starts refusing ungranted hosts and connections that bypass the proxy ([plan](https://github.com/samclement/swhurl-platform/blob/main/docs/plan.md#stage-5-egress-grants)).
- **Other apps, events, notifications and telemetry** stay inside the cluster and never use the proxy (`NO_PROXY`).
- **Clients that use the proxy with no code:** `java.net.http.HttpClient` with its default proxy selector (what `platform.call` uses) and any client that reads the JVM's `https.proxyHost` and `http.nonProxyHosts` properties, which the platform sets in `JAVA_TOOL_OPTIONS`.
- **Micronaut's HTTP client ignores them**: it uses no proxy unless configured. If you add `micronaut-http-client`, give it the JVM's selector:

  ```kotlin
  @Singleton
  class UseTheProxy : BeanCreatedEventListener<HttpClientConfiguration> {
      override fun onCreated(event: BeanCreatedEvent<HttpClientConfiguration>): HttpClientConfiguration =
          event.bean.apply { setProxySelector(java.net.ProxySelector.getDefault()) }
  }
  ```
- **An SDK with its own HTTP client** (a payment or cloud provider's): check that it follows the JVM's proxy properties, or give it the proxy from `HTTPS_PROXY` in its own settings.
- **Locally** there is no proxy: the variables are unset and requests go direct.

## Notifications

`platform.notify("Order backlog", message = "412 orders waiting", priority = "high")` (in `Platform.kt`) sends a message to the notification channel of the team that owns this app ([notifications](https://github.com/samclement/swhurl-platform/blob/main/docs/apps.md#notifications)). Nothing is declared in `swhurl.yaml`: the platform gives every app the address and a token. The message arrives titled `<app>/<env>: Order backlog`. The call returns whether the platform accepted it and never throws; an app may send 30 an hour, and a team without a channel delivers nothing. `POST /notify` in `HttpController.kt` is an example to replace. Locally there is no notify service: `notify` returns `false` and sends nothing.

## Feature flags

Declare a flag in `swhurl.yaml` with a type, a default and, where it differs, a value for `staging` or `prod` ([feature flags](https://github.com/samclement/swhurl-platform/blob/main/docs/apps.md#feature-flags)):

```yaml
flags:
  new-checkout: {type: boolean, description: Use the new checkout flow, default: false, staging: true}
```

Before every compile, `./gradlew flags` (`gradle/flags.gradle.kts`) checks the declarations and generates the `Flags` object under `build/generated/flags`: `if (Flags.newCheckout)`. On the platform an override set from the console wins: the app watches its own overrides over NATS (`FlagWatcher.kt`) and the next read returns the new value, without a restart. Otherwise a flag is the value declared for `SWHURL_ENV`, else its default, which is also what local runs get. A restart keeps the override; `/healthz` reports not ready until the stored overrides arrive (at most 5 s). Types: `boolean`, `integer` (`Int`), `number` (`Double`; both with optional `min` and `max`) and `string` (with an optional `enum`). To remove a flag, delete it from `swhurl.yaml`: the compiler then names each place that still reads it.

## Events

`Events.kt` publishes and receives events on the platform's NATS ([events](https://github.com/samclement/swhurl-platform/blob/main/docs/apps.md#events)). What it publishes is an offer: declare it in `swhurl.yaml` before the app is created (afterwards: `make app-events` on the platform):

```yaml
events:
  publish: [created]               # other apps see swhurl-try-20.created
```

What it receives is a grant, set on the platform: tick `orders.created` under **Connections** when creating the app, or later use the app page's **Connect to an app** or `make app-events ARGS="--add-subscribe orders.created"`.

Then `events.publish("created", mapOf("id" to 42))` and `events.subscribe("orders.created") { event -> ... }`. An event is a CloudEvents envelope (`event.data` is what was published); a handler runs inside the publisher's trace, and if it throws the event comes back, five tries in all. `subscribe` keeps trying until it is receiving, since a new app can start before NATS knows it. `HttpController.kt` publishes the first declared event on every request to `/`: an example to replace. Locally there is no NATS: `publish` returns `null` and `subscribe` never calls back.

## Without the platform

Locally there are no platform keys or tokens: requests without a token work as before and a request with one is refused; to call another app, set `SWHURL_SERVICE_<NAME>_URL` to a local stub.

## Local development

Needs a JDK 25 (Gradle downloads everything else):

```bash
./gradlew run                           # the agent stays off locally (the image loads it)
curl http://localhost:8080/healthz
./gradlew check                         # compile and tests, as CI does
```
