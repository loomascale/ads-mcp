package com.loomascale.googleads.mcp.connect;

import com.loomascale.mcp.spi.AdsConnection;
import com.loomascale.mcp.spi.AdsConnectionState;
import com.loomascale.mcp.spi.AdsTarget;
import java.util.List;
import org.springframework.stereotype.Component;

// The connections page: connect a Google Ads account, see which accounts were found, and
// choose the one tool calls should use.
//
// Plain HTML from a string template, for the same reason as the consent page: a library
// that contributes a view technology breaks the host's own rendering, and there are only a
// handful of dynamic values. Every one is escaped — account names come from Google and are
// ultimately user-authored.
@Component
public class ConnectPageRenderer {

  public String connections(AdsConnection connection, List<AdsTarget> targets) {
    StringBuilder body = new StringBuilder();

    if (connection == null) {
      body.append("<p>No Google Ads account is connected yet.</p>")
          .append(form("/connect/google", "Connect Google Ads", null));
    } else {
      body.append("<p>Status: <strong>")
          .append(escape(describe(connection.state())))
          .append("</strong></p>");

      if (targets.isEmpty()) {
        body.append(
            "<p>No manageable ad accounts were found. Manager (MCC) accounts cannot run"
                + " ads themselves, so only their child accounts appear here.</p>");
      } else {
        body.append(
            "<p>Choose the account tool calls should use:</p><form method=\"post\""
                + " action=\"/connections/select\"><ul class=\"accounts\">");
        for (AdsTarget target : targets) {
          boolean selected = target.id().equals(connection.selectedTargetId());
          body.append("<li><label><input type=\"radio\" name=\"target_id\" value=\"")
              .append(escape(target.id()))
              .append("\"")
              .append(selected ? " checked" : "")
              .append("> ")
              .append(escape(target.name()))
              .append(target.currency() == null ? "" : " (" + escape(target.currency()) + ")")
              .append(" <span class=\"id\">")
              .append(escape(target.id()))
              .append("</span></label></li>");
        }
        body.append(
            "</ul><label for=\"pw\">Operator password<input id=\"pw\""
                + " name=\"operator_password\" type=\"password\" autocomplete=\"current-password\""
                + " required></label><button type=\"submit\">Use this account</button></form>");
      }
      body.append(
          form(
              "/connect/google",
              "Reconnect",
              "Reconnecting refreshes the account"
                  + " list and the granted permissions. It does not lose your selection."));
    }
    return page("Connections", body.toString());
  }

  public String signIn(String error) {
    String message =
        error == null ? "" : "<p class=\"error\" role=\"alert\">" + escape(error) + "</p>";
    return page(
        "Sign in",
        message
            + "<p>Enter the operator password to manage connections.</p>"
            + form("/connections", "Continue", null));
  }

  public String message(String text) {
    return page(
        "Connections",
        "<p>" + escape(text) + "</p>" + "<p><a href=\"/connections\">Back to connections</a></p>");
  }

  private String form(String action, String label, String note) {
    return "<form method=\"post\" action=\""
        + escape(action)
        + "\"><label for=\"op\">Operator password<input id=\"op\" name=\"operator_password\""
        + " type=\"password\" autocomplete=\"current-password\" required></label>"
        + "<button type=\"submit\">"
        + escape(label)
        + "</button>"
        + (note == null ? "" : "<p class=\"fine\">" + escape(note) + "</p>")
        + "</form>";
  }

  private static String describe(AdsConnectionState state) {
    if (state == AdsConnectionState.CONNECTED) {
      return "connected";
    }
    if (state == AdsConnectionState.NO_AD_ACCOUNT) {
      return "connected, but this Google account owns no Google Ads account";
    }
    return "expired — reconnect to continue";
  }

  private String page(String title, String body) {
    return """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <meta name="robots" content="noindex, nofollow">
          <title>%TITLE%</title>
          <style>
            :root { color-scheme: light dark; }
            body { margin: 0; min-height: 100vh; display: grid; place-items: center;
                   font: 16px/1.5 system-ui, -apple-system, Segoe UI, sans-serif;
                   background: Canvas; color: CanvasText; }
            main { width: min(34rem, 92vw); padding: 2rem; border: 1px solid
                   color-mix(in srgb, CanvasText 20%, transparent); border-radius: 12px; }
            h1 { font-size: 1.25rem; margin: 0 0 1rem; }
            ul.accounts { list-style: none; padding: 0; }
            ul.accounts li { padding: .35rem 0; }
            .id { font-family: ui-monospace, Menlo, monospace; font-size: .8125rem;
                  color: color-mix(in srgb, CanvasText 60%, transparent); }
            label { display: block; margin-top: 1rem; font-weight: 600; }
            input[type=password] { width: 100%; padding: .6rem; margin-top: .35rem;
                   font: inherit; border-radius: 8px; box-sizing: border-box;
                   border: 1px solid color-mix(in srgb, CanvasText 35%, transparent);
                   background: Canvas; color: CanvasText; }
            button { margin-top: 1rem; padding: .7rem 1rem; font: inherit;
                     border-radius: 8px; cursor: pointer; background: CanvasText;
                     color: Canvas; border: 1px solid CanvasText; }
            form { margin-top: 1.5rem; border-top: 1px solid
                   color-mix(in srgb, CanvasText 15%, transparent); padding-top: .5rem; }
            .error { color: #b00020; font-weight: 600; }
            .fine { font-size: .875rem;
                    color: color-mix(in srgb, CanvasText 65%, transparent); }
            a { color: inherit; }
          </style>
        </head>
        <body><main><h1>%TITLE%</h1>%BODY%</main></body>
        </html>
        """
        .replace("%TITLE%", escape(title))
        .replace("%BODY%", body);
  }

  // Account names come from Google and are ultimately typed by a user, so they are escaped
  // like any other untrusted text.
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
