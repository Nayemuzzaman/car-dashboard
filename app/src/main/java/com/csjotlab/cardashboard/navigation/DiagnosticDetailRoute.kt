package com.csjotlab.cardashboard.navigation

import java.net.URLEncoder

object DiagnosticDetailRoute {
    const val ARG = "issueId"
    const val route = "diagnostics/{$ARG}"

    /**
     * Escapes the issue id into a single path segment.
     *
     * Two details matter and both are load-bearing:
     *
     *  - `NavController.navigate(String)` does **not** escape the route it is handed, so an id
     *    containing `/` would silently split the path and the destination would stop matching;
     *  - Navigation unescapes the captured argument with `Uri.decode`, which treats `+` as a
     *    literal plus. `URLEncoder` emits `+` for a space (it targets
     *    `application/x-www-form-urlencoded`, not a URI path), so the space escape is corrected to
     *    `%20` here. The route this produces therefore round-trips through `Uri.decode` exactly,
     *    which is why the destination must **not** decode a second time.
     */
    fun of(issueId: String): String =
        "diagnostics/" + URLEncoder.encode(issueId, "UTF-8").replace("+", "%20")
}
