package me.Luki.WalkieTalkiePlugin.radio;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

public enum RadioChannel {
    CZERWONI("czerwoni"),
    NIEBIESCY("niebiescy"),
    HANDLARZE("handlarze"),
    PIRACI("piraci"),
    TOHANDLARZE("tohandlarze"),
    PIRACI_RANDOM("randomchannel.piraci");

    private final String id;
    private final UUID voicechatChannelId;

    RadioChannel(String id) {
        this.id = id;
        this.voicechatChannelId = UUID.nameUUIDFromBytes(("walkietalkie:" + id).getBytes(StandardCharsets.UTF_8));
    }

    public String id() {
        return id;
    }

    public UUID voicechatChannelId() {
        return voicechatChannelId;
    }

    public String usePermission() {
        if (this == PIRACI_RANDOM) {
            return "radio.use.randomchannel.piraci";
        }
        return "radio.use." + id;
    }

    public String listenPermission() {
        // For PIRACI_RANDOM we use the dedicated permission from spec
        if (this == PIRACI_RANDOM) {
            return "radio.listen.randomchannel.piraci";
        }
        return "radio.listen." + id;
    }

    public String craftPermission() {
        if (this == PIRACI_RANDOM) {
            return "radio.craft.randomchannel.piraci";
        }
        return "radio.craft." + id;
    }

    public static RadioChannel fromId(String id) {
        if (id == null) {
            return null;
        }
        String normalized = id.toLowerCase(Locale.ROOT).trim();
        for (RadioChannel channel : values()) {
            if (channel.id.equals(normalized)) {
                return channel;
            }
        }
        return null;
    }
}
