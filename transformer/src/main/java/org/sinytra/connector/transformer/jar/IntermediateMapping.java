package org.sinytra.connector.transformer.jar;

import com.mojang.logging.LogUtils;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.MappingResolverImpl;
import net.minecraftforge.srgutils.IMappingFile;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.*;
import java.util.stream.Stream;

public class IntermediateMapping {
    private static final Map<String, IntermediateMapping> INTERMEDIATE_MAPPINGS_CACHE = new HashMap<>();
    
    // 添加新组件相关的映射前缀
    private static final Map<String, Collection<String>> MAPPING_PREFIXES = Map.of(
        "intermediary", Set.of(
            "net/minecraft/class_", 
            "field_", 
            "method_", 
            "comp_",
            "component_"  // 添加组件系统的新前缀
        )
    );
    
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Map<String, String> mappings;
    private final Map<String, String> extendedMappings;

    public static IntermediateMapping get(String sourceNamespace) {
        IntermediateMapping existing = INTERMEDIATE_MAPPINGS_CACHE.get(sourceNamespace);
        if (existing == null) {
            synchronized (IntermediateMapping.class) {  // 修复同步锁
                existing = INTERMEDIATE_MAPPINGS_CACHE.get(sourceNamespace);
                if (existing != null) {
                    return existing;
                }

                LOGGER.debug(JarTransformer.TRANSFORM_MARKER, 
                    "Creating flat intermediate mapping for namespace {}", sourceNamespace);
                
                Collection<String> prefixes = MAPPING_PREFIXES.getOrDefault(
                    sourceNamespace, Collections.emptySet()
                );
                
                MappingResolverImpl resolver = FabricLoaderImpl.INSTANCE.getMappingResolver();
                Map<String, String> resolved = new HashMap<>();
                Map<String, IMappingFile.INode> buffer = new HashMap<>();
                Map<String, String> extendedMappings = new HashMap<>();
                
                // 添加组件类的特殊处理
                Set<String> componentClasses = new HashSet<>(Arrays.asList(
                    "net/minecraft/core/component/DataComponentType",
                    "net/minecraft/core/component/DataComponents"
                ));
                
                resolver.getCurrentMap(sourceNamespace).getClasses().stream()
                    .flatMap(cls -> {
                        Stream<IMappingFile.INode> stream = Stream.concat(
                            Stream.of(cls),
                            Stream.concat(cls.getFields().stream(), cls.getMethods().stream())
                        );
                        
                        // 特殊处理组件类
                        if (componentClasses.contains(cls.getOriginal())) {
                            return stream; // 不过滤前缀
                        }
                        
                        return stream.filter(node -> 
                            prefixes.stream().anyMatch(prefix -> 
                                node.getOriginal().startsWith(prefix) || 
                                node.getMapped().startsWith(prefix)
                        );
                    })
                    .forEach(node -> {
                        String original = node.getOriginal();
                        String mapped = node.getMapped();
                        String mapping = resolved.get(original);
                        
                        // 添加组件方法的特殊映射键
                        String mappingKey = getMappingKey(node);
                        if (mappingKey.contains("get(Lnet/minecraft/core/component/DataComponentType;")) {
                            mappingKey = mappingKey.replace(
                                "get(Lnet/minecraft/core/component/DataComponentType;",
                                "get_component_"
                            );
                        }
                        
                        if (mapping != null && !mapping.equals(mapped)) {
                            resolved.remove(original);
                            extendedMappings.put(mappingKey, mapping);
                            extendedMappings.put(mappingKey, mapped);
                        }
                        else if (!extendedMappings.containsKey(mappingKey)) {
                            resolved.put(original, mapped);
                            buffer.put(original, node);
                        }
                    });
                
                IntermediateMapping mapping = new IntermediateMapping(resolved, extendedMappings);
                INTERMEDIATE_MAPPINGS_CACHE.put(sourceNamespace, mapping);
                return mapping;
            }
        }
        return existing;
    }

    private static String getMappingKey(IMappingFile.INode node) {
        if (node instanceof IMappingFile.IField field) {
            String desc = field.getDescriptor();
            // 处理组件字段的特殊描述符
            if (desc != null && desc.contains("DataComponentType")) {
                return field.getOriginal() + ":component_type";
            }
            return field.getOriginal() + (desc != null ? ":" + desc : "");
        }
        else if (node instanceof IMappingFile.IMethod method) {
            String desc = method.getDescriptor();
            // 处理组件方法的特殊描述符
            if (desc != null && desc.contains("DataComponentType")) {
                return method.getOriginal() + "_component_method";
            }
            return method.getOriginal() + Objects.requireNonNullElse(desc, "");
        }
        return node.getOriginal();
    }

    public IntermediateMapping(Map<String, String> mappings, Map<String, String> extendedMappings) {
        this.mappings = mappings;
        this.extendedMappings = extendedMappings;
    }

    @Nullable
    public String map(String name) {
        // 添加组件类型的特殊映射
        if (name.startsWith("net/minecraft/core/component/")) {
            String mapped = this.mappings.get(name);
            if (mapped == null) {
                return name.replace("component/", "core/component/");
            }
            return mapped;
        }
        return this.mappings.get(name);
    }

    @Nullable
    public String mapField(String name, @Nullable String desc) {
        // 处理组件字段的特殊情况
        if (desc != null && desc.contains("DataComponentType")) {
            String qualifier = name + ":component_type";
            return this.extendedMappings.getOrDefault(qualifier, this.mappings.get(name));
        }
        
        String mapped = this.mappings.get(name);
        if (mapped == null) {
            String qualifier = name + ":" + desc;
            return this.extendedMappings.get(qualifier);
        }
        return mapped;
    }

    public String mapMethodOrDefault(String name, String desc) {
        // 处理组件方法的特殊映射
        if (desc.contains("DataComponentType")) {
            String key = name + "_component_method";
            return this.extendedMappings.getOrDefault(key, name);
        }
        String mapped = mapMethod(name, desc);
        return mapped != null ? mapped : name;
    }

    @Nullable
    public String mapMethod(String name, String desc) {
        // 优先处理组件相关方法
        if (desc.contains("DataComponentType")) {
            String key = name + "_component_method";
            return this.extendedMappings.get(key);
        }
        
        String mapped = this.mappings.get(name);
        if (mapped == null) {
            String qualifier = name + desc;
            return this.extendedMappings.get(qualifier);
        }
        return mapped;
    }
}
