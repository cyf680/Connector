package org.sinytra.connector.transformer.patch;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.sinytra.adapter.patch.api.Patch;
import org.sinytra.adapter.patch.util.MethodQualifier;

import java.util.Map;

public class ClassAnalysingTransformer implements ClassNodeTransformer.ClassProcessor {
    // 更新方法替换规则以适应 1.21.6 的配置系统变化
    private static final Map<MethodQualifier, MethodQualifier> REPLACEMENTS = Map.of(
        // 保持 Class.getResourceAsStream 替换不变
        new MethodQualifier("Ljava/lang/Class;", "getResourceAsStream", "(Ljava/lang/String;)Ljava/io/InputStream;"),
        new MethodQualifier("org/sinytra/connector/mod/ConnectorMod", "getModResourceAsStream", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/io/InputStream;"),

        // 更新 NightConfig 相关方法替换
        new MethodQualifier("Lnet/neoforged/neoforge/common/NightConfigFileConfigBuilder;", "defaultResource", "(Ljava/lang/String;)Lnet/neoforged/neoforge/common/NightConfigFileConfigBuilder$GenericBuilder;"),
        new MethodQualifier("org/sinytra/connector/mod/ConnectorMod", "useModConfigResource", "(Lnet/neoforged/neoforge/common/NightConfigFileConfigBuilder;Ljava/lang/String;)Lnet/neoforged/neoforge/common/NightConfigFileConfigBuilder$GenericBuilder;"),
        
        // 添加组件系统相关的方法替换
        new MethodQualifier("Lnet/minecraft/world/item/ItemStack;", "getItem", "()Lnet/minecraft/world/item/Item;"),
        new MethodQualifier("org/sinytra/connector/mod/ConnectorMod", "getItemComponent", "(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/Item;"),
        
        new MethodQualifier("Lnet/minecraft/core/Holder$Reference;", "value", "()Ljava/lang/Object;"),
        new MethodQualifier("org/sinytra/connector/mod/ConnectorMod", "getHolderValue", "(Lnet/minecraft/core/Holder$Reference;)Ljava/lang/Object;")
    );

    @Override
    public Patch.Result process(ClassNode node) {
        boolean applied = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode minsn) {
                    for (Map.Entry<MethodQualifier, MethodQualifier> entry : REPLACEMENTS.entrySet()) {
                        if (entry.getKey().matches(minsn)) {
                            MethodQualifier replacement = entry.getValue();
                            // 添加组件系统方法的特殊处理
                            if (replacement.name().contains("Component")) {
                                method.instructions.insertBefore(insn, new MethodInsnNode(
                                    Opcodes.GETSTATIC,
                                    "net/minecraft/core/component/DataComponents",
                                    "ITEM",
                                    "Lnet/minecraft/core/component/DataComponentType;"
                                ));
                            }
                            
                            method.instructions.set(insn, new MethodInsnNode(
                                Opcodes.INVOKESTATIC, 
                                replacement.owner(), 
                                replacement.name(), 
                                replacement.desc(), 
                                false
                            ));
                            
                            applied = true;
                        }
                    }
                }
            }
        }
        return applied ? Patch.Result.APPLY : Patch.Result.PASS;
    }
}
