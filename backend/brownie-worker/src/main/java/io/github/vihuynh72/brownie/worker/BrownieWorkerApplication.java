package io.github.vihuynh72.brownie.worker;

import io.github.vihuynh72.brownie.ai.generation.json.CompositionResponseParserConfig;
import io.github.vihuynh72.brownie.ai.generation.json.ExtractionResponseParserConfig;
import io.github.vihuynh72.brownie.ai.openai.OpenAiModelGatewayConfig;
import io.github.vihuynh72.brownie.worker.config.BrownieEnvironmentListener;
import io.github.vihuynh72.brownie.storage.azure.AzureBlobStorageConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({
        AzureBlobStorageConfig.class, OpenAiModelGatewayConfig.class, ExtractionResponseParserConfig.class,
        CompositionResponseParserConfig.class})
public class BrownieWorkerApplication {

    static final String MODE_PROPERTY = "brownie.worker.mode";
    /** The same setting as {@link #MODE_PROPERTY}, read before any property source exists. */
    static final String MODE_ENVIRONMENT_VARIABLE = "BROWNIE_WORKER_MODE";
    static final String SERVE = "serve";
    static final String REPLAY_DELETIONS = "replay-deletions";

    /**
     * Started normally the worker serves until it is stopped. Started in
     * its one other mode it does that one thing and ends, with the status
     * that thing reported. A mode it does not know never gets this far: the
     * environment listener refuses it before any bean exists, because a
     * worker that started, did nothing and ended with status 0 would read,
     * to a restore script, exactly like a replay that succeeded.
     */
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(BrownieWorkerApplication.class);
        application.addListeners(new BrownieEnvironmentListener());
        if (REPLAY_DELETIONS.equals(System.getenv(MODE_ENVIRONMENT_VARIABLE))) {
            // A replay listens to nobody: it does one thing and ends. Left as a
            // web application it would try to bind the management port, which
            // the worker that is already serving is holding -- so a replay run
            // beside a running worker, which is exactly what a restore
            // rehearsal does, would fail to start for a reason that has
            // nothing to do with the restore.
            application.setWebApplicationType(WebApplicationType.NONE);
        }
        ConfigurableApplicationContext context = application.run(args);
        if (REPLAY_DELETIONS.equals(context.getEnvironment().getProperty(MODE_PROPERTY, SERVE))) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
