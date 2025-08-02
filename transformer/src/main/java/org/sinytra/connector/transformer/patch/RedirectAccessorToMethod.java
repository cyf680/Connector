package org.sinytra.connector.transformer.patch;

import com.mojang.logging.LogUtils;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.sinytra.adapter.patch.api.*;
import org.sinytra.connector.transformer.TransformerEnvironment;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.util.Collection;
import java.util.Set;

import static org.sinytra.adapter.patch.PatchInstance.MIXINPATCH;

public record RedirectAccessorToMethod(String value, TransformerEnvironment environment) implements MethodTransform {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Marker COMPONENT_MARKER = MarkerFactory.getMarker("COMPONENT");
    
    @Override
    public Collection<String> getAcceptedAnnotations() {
        return Set.of(MixinConstants.ACCESSOR);
    }

    @Override
    public Patch.Result apply(ClassNode classNode, MethodNode methodNode, MethodContext methodContext, PatchContext context) {
        // 组件系统特殊处理：如果重定向到 getItem() 方法，则使用组件API
        String targetMethod = this.value;
        if (isItemAccessor(methodNode, classNode.name)) {
            targetMethod = "getComponentItem";
            LOGGER.debug(COMPONENT_MARKER, "Redirecting ItemStack accessor to component API: {}.{} -> {}", 
                        classNode.name, methodNode.name, targetMethod);
        }
        
        AnnotationVisitor visitor = methodNode.visitAnnotation(MixinConstants.INVOKER, true);
        visitor.visit("value", targetMethod);
        visitor.visitEnd();

        methodNode.visibleAnnotations.remove(methodContext.methodAnnotation().unwrap());

        LOGGER.info(MIXINPATCH, "Redirecting accessor {}.{} to invoke method {}", 
                   classNode.name, methodNode.name, targetMethod);

        return Patch.Result.APPLY;
    }
    
    private boolean isItemAccessor(MethodNode method, String className) {
        // 识别 ItemStack 相关的访问器
        return className.equals("net/minecraft/world/item/ItemStack") && 
               method.name.equals("getItem") && 
               method.desc.equals("()Lnet/minecraft/world/item/Item;");
    }
    
    // 在 ConnectorMod 中需要添加的辅助方法
    public static class ComponentAccessors {
        public static net.minecraft.world.item.Item getComponentItem(net.minecraft.world.item.ItemStack stack) {
            return stack.get(net.minecraft.core.component.DataComponents.ITEM);
        }
    }
}
