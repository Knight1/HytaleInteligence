package de.tobiassachs;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class DeepceCommand extends AbstractCommand {

    private static final String DEEPCE_URL = "https://raw.githubusercontent.com/stealthcopter/deepce/main/deepce.sh";

    public DeepceCommand(String name, String description) {
        super(name, description);
        requirePermission("hytale.intelligence.shell");
        setAllowsExtraArguments(true);
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {

        String input = context.getInputString().trim();
        String extraArgs = "";
        int spaceIdx = input.indexOf(' ');
        if (spaceIdx >= 0) {
            extraArgs = input.substring(spaceIdx + 1).trim();
        }

        context.sendMessage(Message.raw("---- deepce - Docker Enumeration ----"));
        context.sendMessage(Message.raw("Downloading deepce.sh from GitHub..."));

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(DEEPCE_URL))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                context.sendMessage(Message.raw("Failed to download deepce.sh: HTTP " + response.statusCode()));
                return CompletableFuture.completedFuture(null);
            }

            Path tempScript = Files.createTempFile("deepce", ".sh");
            Files.writeString(tempScript, response.body());
            tempScript.toFile().setExecutable(true);

            context.sendMessage(Message.raw("Downloaded " + response.body().length() + " bytes"));

            String command = "/bin/sh " + tempScript.toAbsolutePath();
            if (!extraArgs.isEmpty()) {
                command += " " + extraArgs;
            }

            context.sendMessage(Message.raw("$ " + command));
            context.sendMessage(Message.raw(""));

            ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            int lineCount = 0;
            while ((line = reader.readLine()) != null) {
                context.sendMessage(Message.raw(line));
                lineCount++;
                if (lineCount >= 5000) {
                    context.sendMessage(Message.raw("... output truncated at 5000 lines"));
                    break;
                }
            }

            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                context.sendMessage(Message.raw("Process timed out after 120s and was killed"));
            } else {
                context.sendMessage(Message.raw("Exit code: " + process.exitValue()));
            }

            Files.deleteIfExists(tempScript);

        } catch (Exception e) {
            context.sendMessage(Message.raw("Error running deepce: " + e.getMessage()));
        }

        return CompletableFuture.completedFuture(null);
    }
}
