package de.tobiassachs;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

public class SessionCommand extends AbstractCommand {

    private static final String[] TOKEN_VARS = {
            "HYTALE_SERVER_SESSION_TOKEN",
            "HYTALE_SERVER_IDENTITY_TOKEN",
            "HYTALE_SERVER_AUDIENCE"
    };

    public SessionCommand(String name, String description) {
        super(name, description);
        requirePermission("hytale.intelligence.info");
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {

        context.sendMessage(Message.raw("---- Session / Auth Tokens ----"));

        for (String varName : TOKEN_VARS) {
            String value = System.getenv(varName);
            if (value == null || value.isEmpty()) {
                context.sendMessage(Message.raw(varName + ": Not set"));
            } else {
                context.sendMessage(Message.raw(varName + ": " + value));

                if (varName.endsWith("_TOKEN")) {
                    decodeJwt(context, varName, value);
                }
            }
        }

        // Also check system properties as fallback
        context.sendMessage(Message.raw(""));
        context.sendMessage(Message.raw("---- Related System Properties ----"));
        String[] props = {
                "hytale.server.session.token",
                "hytale.server.identity.token",
                "hytale.server.audience"
        };
        for (String prop : props) {
            String value = System.getProperty(prop);
            if (value != null && !value.isEmpty()) {
                context.sendMessage(Message.raw(prop + ": " + value));
            } else {
                context.sendMessage(Message.raw(prop + ": Not set"));
            }
        }

        // Check for auth-related env vars
        context.sendMessage(Message.raw(""));
        context.sendMessage(Message.raw("---- Other Auth-Related ENV ----"));
        for (var entry : System.getenv().entrySet()) {
            String key = entry.getKey().toUpperCase();
            if (key.contains("TOKEN") || key.contains("SESSION") || key.contains("AUTH")
                    || key.contains("SECRET") || key.contains("CREDENTIAL") || key.contains("HYTALE")) {
                if (!isKnownTokenVar(entry.getKey())) {
                    context.sendMessage(Message.raw(entry.getKey() + "=" + entry.getValue()));
                }
            }
        }

        return CompletableFuture.completedFuture(null);
    }

    private boolean isKnownTokenVar(String name) {
        for (String known : TOKEN_VARS) {
            if (known.equals(name)) return true;
        }
        return false;
    }

    private void decodeJwt(CommandContext context, String name, String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) {
                context.sendMessage(Message.raw("  (not a valid JWT format)"));
                return;
            }

            context.sendMessage(Message.raw("  ---- JWT Header ----"));
            String header = decodeBase64(parts[0]);
            context.sendMessage(Message.raw("  " + header));

            context.sendMessage(Message.raw("  ---- JWT Payload ----"));
            String payload = decodeBase64(parts[1]);
            context.sendMessage(Message.raw("  " + payload));

            // Extract common JWT fields
            extractField(context, payload, "iss", "Issuer");
            extractField(context, payload, "sub", "Subject");
            extractField(context, payload, "aud", "Audience");
            extractField(context, payload, "exp", "Expires");
            extractField(context, payload, "iat", "Issued At");
            extractField(context, payload, "nbf", "Not Before");
            extractField(context, payload, "jti", "JWT ID");

        } catch (Exception e) {
            context.sendMessage(Message.raw("  (failed to decode JWT: " + e.getMessage() + ")"));
        }
    }

    private String decodeBase64(String encoded) {
        // JWT uses URL-safe base64 without padding
        String padded = encoded;
        int mod = padded.length() % 4;
        if (mod > 0) {
            padded += "====".substring(mod);
        }
        byte[] decoded = Base64.getUrlDecoder().decode(padded);
        return new String(decoded);
    }

    private void extractField(CommandContext context, String json, String field, String label) {
        // Simple JSON field extraction without a JSON library
        String search = "\"" + field + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return;

        int colonIdx = json.indexOf(':', idx + search.length());
        if (colonIdx < 0) return;

        int valueStart = colonIdx + 1;
        while (valueStart < json.length() && json.charAt(valueStart) == ' ') valueStart++;

        if (valueStart >= json.length()) return;

        String value;
        if (json.charAt(valueStart) == '"') {
            int valueEnd = json.indexOf('"', valueStart + 1);
            if (valueEnd < 0) return;
            value = json.substring(valueStart + 1, valueEnd);
        } else {
            int valueEnd = valueStart;
            while (valueEnd < json.length() && json.charAt(valueEnd) != ',' && json.charAt(valueEnd) != '}') {
                valueEnd++;
            }
            value = json.substring(valueStart, valueEnd).trim();
        }

        // If it looks like a unix timestamp, also show human-readable
        if ((field.equals("exp") || field.equals("iat") || field.equals("nbf")) && value.matches("\\d+")) {
            long epoch = Long.parseLong(value);
            java.util.Date date = new java.util.Date(epoch * 1000);
            context.sendMessage(Message.raw("    " + label + ": " + value + " (" + date + ")"));
        } else {
            context.sendMessage(Message.raw("    " + label + ": " + value));
        }
    }
}
