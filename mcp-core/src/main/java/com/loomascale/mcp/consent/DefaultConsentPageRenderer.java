package com.loomascale.mcp.consent;

import com.loomascale.mcp.oauth.OAuthAuthorizationService.ConsentInfo;

// The bundled consent screen: one self-contained HTML document, no assets, no JavaScript.
//
// Every interpolated value is HTML-escaped. clientName in particular is
// attacker-controlled — dynamic client registration is open, so anyone can register a
// client called "<script>...". The redirect host is shown prominently for the same reason:
// a friendly client name proves nothing, and the host is what the user is really trusting.
public class DefaultConsentPageRenderer implements ConsentPageRenderer {

  private final String productName;
  private final String consentPath;

  public DefaultConsentPageRenderer(String productName, String consentPath) {
    this.productName = productName;
    this.consentPath = consentPath;
  }

  @Override
  public String renderHtml(ConsentInfo info, String requestId, String errorMessage) {
    StringBuilder scopes = new StringBuilder();
    for (String scope : info.scopes()) {
      scopes.append("<li>").append(escape(scope)).append("</li>");
    }
    String error =
        errorMessage == null
            ? ""
            : "<p class=\"error\" role=\"alert\">" + escape(errorMessage) + "</p>";

    return """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <meta name="robots" content="noindex, nofollow">
          <title>Authorize access — %PRODUCT%</title>
          <style>
            :root { color-scheme: light dark; }
            body { margin: 0; min-height: 100vh; display: grid; place-items: center;
                   font: 16px/1.5 system-ui, -apple-system, Segoe UI, sans-serif;
                   background: Canvas; color: CanvasText; }
            main { width: min(30rem, 92vw); padding: 2rem; border: 1px solid;
                   border-color: color-mix(in srgb, CanvasText 20%, transparent);
                   border-radius: 12px; }
            h1 { font-size: 1.25rem; margin: 0 0 1rem; }
            .host { font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
                    word-break: break-all; }
            ul { padding-left: 1.25rem; }
            .error { color: #b00020; font-weight: 600; }
            form { display: flex; gap: .75rem; margin-top: 1.5rem; }
            button { flex: 1; padding: .7rem 1rem; font: inherit; border-radius: 8px;
                     border: 1px solid; cursor: pointer; }
            .approve { background: CanvasText; color: Canvas; border-color: CanvasText; }
            .deny { background: transparent; color: CanvasText;
                    border-color: color-mix(in srgb, CanvasText 35%, transparent); }
            label { display: block; margin-top: 1.25rem; font-weight: 600; }
            input { width: 100%; padding: .6rem; margin-top: .35rem; font: inherit;
                    border-radius: 8px; border: 1px solid
                    color-mix(in srgb, CanvasText 35%, transparent);
                    background: Canvas; color: CanvasText; box-sizing: border-box; }
            .fine { font-size: .875rem;
                    color: color-mix(in srgb, CanvasText 65%, transparent); }
          </style>
        </head>
        <body>
          <main>
            <h1>Authorize access</h1>
            %ERROR%
            <p><strong>%CLIENT%</strong> is asking to connect to %PRODUCT%.</p>
            <p class="fine">It will be sent back to <span class="host">%HOST%</span>.
               Only continue if you recognise that address.</p>
            <p>It is requesting:</p>
            <ul>%SCOPES%</ul>
            <form method="post" action="%PATH%/%REQUEST%/decide">
              <label for="op">Operator password
                <input id="op" name="operator_password" type="password"
                       autocomplete="current-password" required autofocus>
              </label>
              <div style="display:flex; gap:.75rem; width:100%; margin-top:1.25rem">
                <button class="deny" name="decision" value="deny" type="submit">Deny</button>
                <button class="approve" name="decision" value="approve" type="submit">Approve</button>
              </div>
            </form>
          </main>
        </body>
        </html>
        """
        .replace("%PRODUCT%", escape(productName))
        .replace("%CLIENT%", escape(info.clientName()))
        .replace("%HOST%", escape(info.redirectHost()))
        .replace("%SCOPES%", scopes.toString())
        .replace("%REQUEST%", escape(requestId))
        .replace("%PATH%", escape(consentPath))
        .replace("%ERROR%", error);
  }

  // Escapes the five characters that matter in both element text and quoted attributes.
  private static String escape(String value) {
    if (value == null) {
      return "";
    }
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }
}
