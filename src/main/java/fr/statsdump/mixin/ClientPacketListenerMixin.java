package fr.statsdump.mixin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundAwardStatsPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatType;
import net.minecraft.stats.StatsCounter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private Connection connection;

    private static final Gson STATSDUMP_GSON = new GsonBuilder().setPrettyPrinting().create();

    // À la connexion : on demande les stats au serveur (comme l'onglet Statistiques).
    @Inject(method = "handleLogin", at = @At("TAIL"))
    private void statsdump$request(ClientboundLoginPacket packet, CallbackInfo ci) {
        this.connection.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS));
    }

    // Quand le serveur répond : on exporte en JSON.
    @Inject(method = "handleAwardStats", at = @At("TAIL"))
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void statsdump$dump(ClientboundAwardStatsPacket packet, CallbackInfo ci) {
        try {
            if (this.minecraft.player == null) return;
            StatsCounter counter = this.minecraft.player.getStats();
            JsonObject root = new JsonObject();

            for (StatType type : BuiltInRegistries.STAT_TYPE) {
                Registry<Object> reg = type.getRegistry();
                JsonObject section = new JsonObject();
                for (Object value : reg) {
                    Stat<Object> stat = type.get(value);
                    int v = counter.getValue(stat);
                    if (v != 0) {
                        section.addProperty(String.valueOf(reg.getKey(value)), v);
                    }
                }
                if (section.size() > 0) {
                    root.add(String.valueOf(BuiltInRegistries.STAT_TYPE.getKey(type)), section);
                }
            }

            String server = this.minecraft.getCurrentServer() != null
                    ? this.minecraft.getCurrentServer().ip : "solo";
            server = server.replaceAll("[^a-zA-Z0-9._-]", "_");
            String name = "stats-" + server + "-"
                    + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".json";
            String json = STATSDUMP_GSON.toJson(root);

            Path[] candidates = {
                    Path.of("/storage/emulated/0/Download"),
                    Path.of("/sdcard/Download"),
                    Path.of(System.getProperty("user.home", "."), "Downloads"),
                    this.minecraft.gameDirectory.toPath()
            };
            for (Path dir : candidates) {
                try {
                    if (!Files.isDirectory(dir)) continue;
                    Path out = dir.resolve(name);
                    Files.writeString(out, json, StandardCharsets.UTF_8);
                    System.out.println("[StatsDump] Stats écrites dans " + out);
                    return;
                } catch (Throwable ignored) { }
            }
            System.err.println("[StatsDump] Impossible d'écrire le fichier.");
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
