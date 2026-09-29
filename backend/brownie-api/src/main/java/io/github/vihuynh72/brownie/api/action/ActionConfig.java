package io.github.vihuynh72.brownie.api.action;

import io.github.vihuynh72.brownie.api.connector.GoogleConnectorSetup;
import io.github.vihuynh72.brownie.core.action.ActionHandler;
import io.github.vihuynh72.brownie.core.action.CalendarEventHandler;
import io.github.vihuynh72.brownie.core.action.CalendarEventProposals;
import io.github.vihuynh72.brownie.core.action.CalendarEventWriter;
import io.github.vihuynh72.brownie.core.action.DocAppendHandler;
import io.github.vihuynh72.brownie.core.action.DocAppendProposals;
import io.github.vihuynh72.brownie.core.action.DriveFileWriter;
import io.github.vihuynh72.brownie.core.action.DriveSaveHandler;
import io.github.vihuynh72.brownie.core.action.DriveSaveProposals;
import io.github.vihuynh72.brownie.core.action.GoogleDocs;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.compile.TemplateFiller;
import io.github.vihuynh72.brownie.core.export.ExportService;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import io.github.vihuynh72.brownie.core.action.ActionRepository;
import io.github.vihuynh72.brownie.core.action.ActionService;
import io.github.vihuynh72.brownie.core.action.ActionType;
import io.github.vihuynh72.brownie.core.connector.ConnectorService;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Wires the one service through which Brownie changes anything in a
 * person's outside account, with a handler for each kind of change.
 *
 * <p>It refuses to start if a request to Google could outlast the time an
 * attempt must still hold its action before sending: otherwise a request
 * that stalled could send after another attempt had taken the action over.
 */
@Configuration
class ActionConfig {

    @Bean
    ActionHandler driveSaveFileHandler(DriveFileWriter driveFileWriter, GoogleDocs googleDocs, ArtifactService artifactService) {
        return new DriveSaveHandler(ActionType.DRIVE_SAVE_FILE, driveFileWriter, googleDocs, artifactService);
    }

    @Bean
    ActionHandler driveSaveAsGoogleDocHandler(DriveFileWriter driveFileWriter, GoogleDocs googleDocs, ArtifactService artifactService) {
        return new DriveSaveHandler(ActionType.DRIVE_SAVE_AS_GOOGLE_DOC, driveFileWriter, googleDocs, artifactService);
    }

    @Bean
    ActionHandler calendarEventHandler(CalendarEventWriter calendarEventWriter) {
        return new CalendarEventHandler(calendarEventWriter);
    }

    @Bean
    CalendarEventProposals calendarEventProposals(
            ActionService actionService, ConnectorService connectorService, RevisionService revisionService) {
        return new CalendarEventProposals(actionService, connectorService, revisionService);
    }

    @Bean
    ActionHandler docAppendHandler(GoogleDocs googleDocs, DriveFileWriter driveFileWriter) {
        return new DocAppendHandler(googleDocs, driveFileWriter);
    }

    @Bean
    DocAppendProposals docAppendProposals(
            ActionService actionService,
            ConnectorService connectorService,
            GoogleDocs googleDocs,
            DriveFileWriter driveFileWriter,
            RevisionService revisionService,
            ExportService exportService,
            ArtifactService artifactService,
            TemplateRepository templateRepository,
            TemplateFiller templateFiller) {
        return new DocAppendProposals(actionService, connectorService, googleDocs, driveFileWriter, revisionService, exportService,
                artifactService, templateRepository, templateFiller);
    }

    @Bean
    DriveSaveProposals driveSaveProposals(
            ActionService actionService,
            ConnectorService connectorService,
            DriveFileWriter driveFileWriter,
            RevisionService revisionService,
            ExportService exportService,
            ArtifactService artifactService,
            TemplateRepository templateRepository,
            TemplateFiller templateFiller) {
        return new DriveSaveProposals(actionService, connectorService, driveFileWriter, revisionService, exportService, artifactService,
                templateRepository, templateFiller);
    }

    @Bean
    ConfiguredActionOffer actionOffer(
            GoogleConnectorSetup googleConnectorSetup,
            ObjectProvider<ActionHandler> handlers,
            @Value("${brownie.connectors.google.actions-offered:false}") boolean actionsOffered) {
        if (!googleConnectorSetup.configured() || !actionsOffered) {
            return new ConfiguredActionOffer(EnumSet.noneOf(ActionType.class), Set.of());
        }
        List<ActionHandler> all = handlers.orderedStream().toList();
        return new ConfiguredActionOffer(
                all.stream().map(ActionHandler::type).collect(Collectors.toSet()),
                all.stream().map(ActionHandler::access).collect(Collectors.toSet()));
    }

    @Bean
    ActionService actionService(
            ActionRepository actionRepository,
            WorkspaceRepository workspaceRepository,
            ConnectorService connectorService,
            ConfiguredActionOffer actionOffer,
            ObjectProvider<ActionHandler> handlers,
            @Value("${brownie.connectors.google.connect-timeout:PT5S}") Duration connectTimeout,
            @Value("${brownie.connectors.google.read-timeout:PT15S}") Duration readTimeout,
            @Value("${brownie.connectors.google.upload-timeout:PT60S}") Duration uploadTimeout) {
        // Uploads have their own deadline; every other change (an event, an addition to a Doc) has a read's.
        Duration longestRequest = connectTimeout.plus(readTimeout.compareTo(uploadTimeout) > 0 ? readTimeout : uploadTimeout);
        if (longestRequest.compareTo(Duration.ofSeconds(ActionService.MIN_SEND_LEASE_SECONDS)) >= 0) {
            throw new IllegalStateException("A request to Google may take up to " + longestRequest.toSeconds()
                    + " s with these timeouts, which is not less than the " + ActionService.MIN_SEND_LEASE_SECONDS
                    + " s an attempt must still hold its action before sending. Shorten brownie.connectors.google.connect-timeout"
                    + ", brownie.connectors.google.read-timeout or brownie.connectors.google.upload-timeout.");
        }
        List<ActionHandler> all = handlers.orderedStream().toList();
        return new ActionService(actionRepository, workspaceRepository, connectorService, actionOffer, all, Clock.systemUTC());
    }
}
