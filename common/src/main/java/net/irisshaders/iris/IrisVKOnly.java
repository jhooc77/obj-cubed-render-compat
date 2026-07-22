package net.irisshaders.iris;

import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public class IrisVKOnly {
    public static final KeyMapping.Category irisKeybindCategory = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("iris", "keybinds"));
    private static boolean initialized;

    public static void run() {
		if (initialized) {
			return;
		}

		initialized = true;
		new Iris().onEarlyInitialize();
    }

    public static void handleKeybinds() {
		Iris.handleKeybinds(Minecraft.getInstance());
    }
}
