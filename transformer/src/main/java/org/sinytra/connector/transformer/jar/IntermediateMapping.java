package org.sinytra.connector.transformer.jar;

import com.mojang.logging.LogUtils;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.MappingResolverImpl;
import net.minecraftforge.srgutils.IMappingFile;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Handles flat intermediate mappings for Minecraft obfuscation layers,
 * with special treatment for data component classes and methods.
 */
public class IntermediateMapping {
    private static final Map<String, IntermediateMapping> INTERMEDIATE_MAPPINGS_CACHE = new ConcurrentHashMap<>();

    // Mapping prefixes for identifying relevant classes/members
    private static final Map<String, Collection<String>> MAPPING_PREFIXES = Map.of(
        "intermediary", Set.of(
            "net/minecraft/class_",
            "field_",
            "method_",
            "comp_",
            "component_"
        )
    );

    private static final Logger LOGGER = LogUtils.getLogger();

    private final Map<String, String> mappings;
    private final Map<String, String> extendedMappings;

    /**
     * Returns the intermediate mapping for the given namespace, with thread safety.
     */
    public static IntermediateMapping get(String sourceNamespace) {
        return INTERMEDIATE_MAPPINGS_CACHE.computeIfAbsent(sourceNamespace, (ns) -> {
            LOGGER.debug(JarTransformer.TRANSFORM_MARKER, "Creating flat intermediate mapping for namespace {}", ns);

            Collection<String> prefixes = MAPPING_PREFIXES.getOrDefault(ns, Collections.emptySet());
            MappingResolverImpl resolver = FabricLoaderImpl.INSTANCE.getMappingResolver();
            Map<String, String> resolved = new HashMap<>();
            Map<String, String> extendedMappings = new HashMap<>();

            // Special case for component classes
            Set<String> componentClasses = Set.of(
                "net/minecraft/core/component/DataComponentType",
                "net/minecraft/core/component/DataComponents"
            );

            resolver.getCurrentMap(ns).getClasses().stream()
                .flatMap(cls -> {
                    // Always include component classes, otherwise filter by prefix
                    Stream<IMappingFile.INode> stream = Stream.concat(
                        Stream.of(cls),
                        Stream.concat(cls.getFields().stream(), cls.getMethods().stream())
                    );

                    if (componentClasses.contains(cls.getOriginal())) {
                        return stream;
                    }
                    return stream.filter(node ->
                        prefixes.stream().anyMatch(prefix ->
                            node.getOriginal().startsWith(prefix) ||
                            node.getMapped().startsWith(prefix)
                        )
                    );
                })
                .forEach(node -> {
                    String original = node.getOriginal();
                    String mapped = node.getMapped();
                    String mappingKey = getMappingKey(node);

                    // Special handling for ambiguous or overloaded mappings
                    if (resolved.containsKey(original) && !resolved.get(original).equals(mapped)) {
                        // Store only the new mapping in extendedMappings (fixes the double-put bug)
                        extendedMappings.put(mappingKey, mapped);
                    } else if (!extendedMappings.containsKey(mappingKey)) {
                        resolved.put(original, mapped);
                    }
                });

            return new IntermediateMapping(resolved, extendedMappings);
        });
    }

    /**
     * Generates a key for extended mapping lookups based on node type and descriptor.
     */
    private static String getMappingKey(IMappingFile.INode node) {
        if (node instanceof IMappingFile.IField field) {
            String desc = field.getDescriptor();
            if (desc != null && desc.contains("DataComponentType")) {
                return field.getOriginal() + ":component_type";
            }
            return desc != null ? (field.getOriginal() + ":" + desc) : field.getOriginal();
        } else if (node instanceof IMappingFile.IMethod method) {
            String desc = method.getDescriptor();
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

    /**
     * Maps a class or member name, with special handling for component types.
     */
    @Nullable
    public String map(String name) {
        if (name.startsWith("net/minecraft/core/component/")) {
            String mapped = this.mappings.get(name);
            if (mapped == null) {
                return name.replace("component/", "core/component/");
            }
            return mapped;
        }
        return this.mappings.get(name);
    }

    /**
     * Maps a field, using its name and descriptor.
     */
    @Nullable
    public String mapField(String name, @Nullable String desc) {
        if (desc != null && desc.contains("DataComponentType")) {
            String qualifier = name + ":component_type";
            return this.extendedMappings.getOrDefault(qualifier, this.mappings.get(name));
        }
        String mapped = this.mappings.get(name);
        if (mapped == null && desc != null) {
            String qualifier = name + ":" + desc;
            return this.extendedMappings.get(qualifier);
        }
        return mapped;
    }

    /**
     * Maps a method (or returns the original if unmapped), with special handling for component methods.
     */
    public String mapMethodOrDefault(String name, String desc) {
        if (desc.contains("DataComponentType")) {
            String key = name + "_component_method";
            return this.extendedMappings.getOrDefault(key, name);
        }
        String mapped = mapMethod(name, desc);
        return mapped != null ? mapped : name;
    }

    /**
     * Maps a method, with special handling for component methods.
     */
    @Nullable
    public String mapMethod(String name, String desc) {
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
