package uz.horecaos.platform.integration.provider.voice;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.provider.ProviderCapabilityCatalog;
import uz.horecaos.platform.integration.api.provider.ProviderCategory;

/**
 * ADR 0026 declaration for the two VOICE adapters this build ships (ADR
 * 0064): the hosted SIP/PBX webhook and the Asterisk-class AMI client.
 *
 * <p>A static map rather than {@code NotificationProviderCapabilityCatalog}'s
 * scan of wired beans, because neither adapter here implements a polymorphic
 * "send" port the way a {@code NotificationChannelAdapter} does — an inbound
 * webhook controller and a socket client are not interchangeable strategy
 * beans, they are two different ingestion mechanisms feeding the same {@link
 * uz.horecaos.platform.voice.api.VoiceEventInboundPort}. Both adapters declare
 * the same one capability, honestly: neither drives a live PBX queue's agent
 * login/logout yet (that is {@code CONSUME_PRESENCE} in ADR 0064's own words,
 * and it needs a real provider account to build against — see ADR 0064's
 * Implementation status), and neither issues call-control commands.
 */
@Component
public class VoiceProviderCapabilityCatalog implements ProviderCapabilityCatalog {

    /** Both adapters push canonical events into the platform; neither is polled. */
    public static final String INGEST_EVENTS_PUSH = "INGEST_EVENTS_PUSH";

    public static final String HOSTED_PBX = "HOSTED_PBX";
    public static final String ASTERISK_AMI = "ASTERISK_AMI";

    private static final Map<String, Declaration> DECLARATIONS = Map.of(
            HOSTED_PBX, new Declaration(Set.of(INGEST_EVENTS_PUSH), "voice/hosted-pbx/v1"),
            ASTERISK_AMI, new Declaration(Set.of(INGEST_EVENTS_PUSH), "voice/asterisk-ami/v1"));

    @Override
    public ProviderCategory category() {
        return ProviderCategory.VOICE;
    }

    @Override
    public Optional<Declaration> declarationFor(String providerType) {
        return Optional.ofNullable(DECLARATIONS.get(providerType));
    }
}
