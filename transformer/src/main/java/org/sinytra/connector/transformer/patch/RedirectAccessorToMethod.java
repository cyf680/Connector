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
        // Special handling for "getItem" accessors in ItemStack
        String targetMethod = this.value;
        if (isItemAccessor(methodNode, classNode.name)) {
            targetMethod = "getComponentItem";
            LOGGER.debug(COMPONENT_MARKER, "Redirecting ItemStack accessor to component API: {}.{} -> {}",
                    classNode.name, methodNode.name, targetMethod);
        }

        AnnotationVisitor visitor = methodNode.visitAnnotation(MixinConstants.INVOKER, true);
        visitor.visit("value", targetMethod);
        visitor.visitEnd();

        if (methodNode.visibleAnnotations != null && methodContext.methodAnnotation() != null) {
            methodNode.visibleAnnotations.remove(methodContext.methodAnnotation().unwrap());
        }

        LOGGER.info(MIXINPATCH, "Redirecting accessor {}.{} to invoke method {}",
                classNode.name, methodNode.name, targetMethod);

        return Patch.Result.APPLY;
    }

    private boolean isItemAccessor(MethodNode method, String className) {
        // Recognize ItemStack's getItem accessor signature
        return "net/minecraft/world/item/ItemStack".equals(className)
                && "getItem".equals(method.name)
                && "()Lnet/minecraft/world/item/Item;".equals(method.desc);
    }

    // Helper method for ItemStack component access.
    // NOTE: The following code will only compile if the Minecraft classes are available on the classpath.
    // If you are building outside a mod dev environment, comment out or guard this block.
    /*
    public static class ComponentAccessors {
        public static net.minecraft.world.item.Item getComponentItem(net.minecraft.world.item.ItemStack stack) {
            return stack.get(net.minecraft.core.component.DataComponents.ITEM);
        }
    }
    */
}
