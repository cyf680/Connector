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
        // 更新 Holder.Reference 转换器：处理 Optional 返回值
        new SimpleTypeAdapter(
            Type.getObjectType("net/minecraft/core/Holder$Reference"), 
            Type.getObjectType("java/lang/Object"), 
            (list, insn) -> {
                // 插入方法调用链：value().orElse(null)
                list.insert(insn, new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, 
                    "net/minecraft/core/Holder$Reference", 
                    "value", 
                    "()Ljava/util/Optional;"
                ));
                list.insert(insn, new InsnNode(Opcodes.ACONST_NULL)); // 压入 null 作为默认值
                list.insert(insn, new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, 
                    "java/util/Optional", 
                    "orElse", 
                    "(Ljava/lang/Object;)Ljava/lang/Object;"
                ));
            }
        ),
        
        // ResourceLocation 转换器保持不变
        new SimpleTypeAdapter(
            Type.getObjectType("net/minecraft/resources/ResourceLocation"), 
            Type.getObjectType("java/lang/String"), 
            (list, insn) -> list.insert(insn, new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, 
                "net/minecraft/resources/ResourceLocation", 
                "toString", 
                "()Ljava/lang/String;"
            ))
        ),
        
        // 更新 ItemStack 转换器：使用 Data Components API
        new SimpleTypeAdapter(
            Type.getObjectType("net/minecraft/world/item/ItemStack"), 
            Type.getObjectType("net/minecraft/world/item/Item"), 
            (list, insn) -> {
                // 插入 DataComponents.ITEM 静态字段访问
                list.insert(insn, new FieldInsnNode(
                    Opcodes.GETSTATIC,
                    "net/minecraft/core/component/DataComponents",
                    "ITEM",
                    "Lnet/minecraft/core/component/DataComponentType;"
                ));
                // 调用 get() 方法获取组件值
                list.insert(insn, new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, 
                    "net/minecraft/world/item/ItemStack", 
                    "get", 
                    "(Lnet/minecraft/core/component/DataComponentType;)Ljava/lang/Object;"
                ));
                // 添加类型转换确保返回 Item 类型
                list.insert(insn, new TypeInsnNode(
                    Opcodes.CHECKCAST, 
                    "net/minecraft/world/item/Item"
                ));
            }
        ),
        
        // 其他转换器保持不变
        new SimpleTypeAdapter(
            Type.getObjectType("net/minecraft/world/entity/Mob"), 
            Type.getObjectType("net/minecraft/world/entity/monster/Monster"), 
            (list, insn) -> {}
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
