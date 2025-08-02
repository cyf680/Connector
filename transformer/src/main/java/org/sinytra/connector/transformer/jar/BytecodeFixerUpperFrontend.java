package org.sinytra.connector.transformer.jar;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.sinytra.connector.transformer.TransformerEnvironment;
import org.sinytra.connector.transformer.transform.TransformerUtil;
import org.sinytra.adapter.patch.fixes.BytecodeFixerUpper;
import org.sinytra.adapter.patch.fixes.SimpleTypeAdapter;
import org.sinytra.adapter.patch.fixes.TypeAdapter;
import org.sinytra.adapter.patch.util.provider.ClassLookup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;

public class BytecodeFixerUpperFrontend {
    private static final List<TypeAdapter> FIELD_TYPE_ADAPTERS = List.of(
        // Holder.Reference → Object: Calls value().orElse(null)
        new SimpleTypeAdapter(
            Type.getObjectType("net/minecraft/core/Holder$Reference"),
            Type.getObjectType("java/lang/Object"),
            (list, insn) -> {
                // Insert value().orElse(null) sequence in correct order
                InsnList patch = new InsnList();
                patch.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/core/Holder$Reference",
                    "value",
                    "()Ljava/util/Optional;"
                ));
                patch.add(new InsnNode(Opcodes.ACONST_NULL));
                patch.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "java/util/Optional",
                    "orElse",
                    "(Ljava/lang/Object;)Ljava/lang/Object;"
                ));
                list.insert(insn, patch);
            }
        ),

        // ResourceLocation → String: Calls toString()
        new SimpleTypeAdapter(
            Type.getObjectType("net/minecraft/resources/ResourceLocation"),
            Type.getObjectType("java/lang/String"),
            (list, insn) -> {
                list.insert(insn, new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/resources/ResourceLocation",
                    "toString",
                    "()Ljava/lang/String;"
                ));
            }
        ),

        // ItemStack → Item: Uses Data Components API and type checks
        new SimpleTypeAdapter(
            Type.getObjectType("net/minecraft/world/item/ItemStack"),
            Type.getObjectType("net/minecraft/world/item/Item"),
            (list, insn) -> {
                InsnList patch = new InsnList();
                patch.add(new FieldInsnNode(
                    Opcodes.GETSTATIC,
                    "net/minecraft/core/component/DataComponents",
                    "ITEM",
                    "Lnet/minecraft/core/component/DataComponentType;"
                ));
                patch.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/world/item/ItemStack",
                    "get",
                    "(Lnet/minecraft/core/component/DataComponentType;)Ljava/lang/Object;"
                ));
                patch.add(new TypeInsnNode(
                    Opcodes.CHECKCAST,
                    "net/minecraft/world/item/Item"
                ));
                list.insert(insn, patch);
            }
        ),

        // Mob → Monster: No-op, for type compatibility
        new SimpleTypeAdapter(
            Type.getObjectType("net/minecraft/world/entity/Mob"),
            Type.getObjectType("net/minecraft/world/entity/monster/Monster"),
            (list, insn) -> { }
        )
    );

    private final BytecodeFixerUpper bfu;
    private final TransformerUtil.CacheFile cacheFile;
    private final Path generatedJarPath;

    public BytecodeFixerUpperFrontend(ClassLookup cleanLookup, ClassLookup dirtyLookup, TransformerEnvironment environment) {
        this.bfu = new BytecodeFixerUpper(cleanLookup, dirtyLookup, FIELD_TYPE_ADAPTERS);

        this.generatedJarPath = environment.getGeneratedJarPath();
        this.cacheFile = TransformerUtil.getCached(null, this.generatedJarPath, environment.getJarCacheVersion());
        if (this.cacheFile.isUpToDate()) {
            this.bfu.getGenerator().loadExisting(this.generatedJarPath);
        }
    }

    public BytecodeFixerUpper unwrap() {
        return this.bfu;
    }

    public void saveGeneratedAdapterJar() throws IOException {
        Files.createDirectories(this.generatedJarPath.getParent());

        Files.deleteIfExists(this.generatedJarPath);
        Attributes attributes = new Attributes();
        attributes.putValue("FMLModType", "GAMELIBRARY");
        if (this.bfu.getGenerator().save(this.generatedJarPath, attributes)) {
            this.cacheFile.save();
        }
    }
}
