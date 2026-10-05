package uz.horecaos.platform.iam.api.protection;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Finds classified data reachable from a type (ADR 0029).
 *
 * <p>Two sources, deliberately combined. A {@link Classified} annotation is
 * authoritative and survives renaming. The name heuristic catches fields nobody
 * remembered to annotate, which is the common case and the reason the interim
 * checks in ADR 0027 and ADR 0032 were name-based to begin with.
 *
 * <p>False positives are the intended direction. A wrongly flagged field costs
 * one annotation or one reviewed exception; a wrongly permitted one puts a phone
 * number on a Kafka topic.
 */
public final class ClassificationScanner {

    private static final Set<String> PROTECTED_TERMS = Set.of(
            "phone",
            "email",
            "passport",
            "birth",
            "dateofbirth",
            "firstname",
            "lastname",
            "middlename",
            "fullname",
            "personname",
            "address",
            "latitude",
            "longitude",
            "coordinate",
            "geolocation",
            "password",
            "secret",
            "token",
            "credential",
            "apikey",
            "cardnumber",
            "pan",
            "cvv",
            "iban",
            "ssn",
            "jshir",
            "tin",
            "note",
            "comment",
            "instructions",
            "devicefingerprint");

    private ClassificationScanner() {}

    /** Every classified path reachable from {@code type}, empty when it is clean. */
    public static List<Finding> scan(Class<?> type, String path) {
        return scan((Type) type, path);
    }

    /**
     * Every classified path reachable from {@code type}, reading generic types as well as
     * classes.
     *
     * <p>A record whose component is a {@code List<Customer>}, a {@code Map<String, Address>},
     * an {@code Optional<Contact>} or an array of records holds those records as surely as a
     * component of the record's own type does, and a response that carries a hundred customers
     * is not cleaner than one that carries a customer. The first version read each component's
     * erased class, which for a collection is {@code List} and not a record, so it stopped
     * there: every list response in the codebase scanned as clean, and the one shape an
     * operator screen is made of was the one shape the classification could not see.
     *
     * <p>Type parameters are followed too. A generic record such as {@code Page<T>(List<T>
     * items)} says nothing about its contents until it is read as {@code Page<Customer>}, so the
     * parameterised type is what a caller should pass whenever it has one.
     *
     * <p>A path through a collection or an array carries {@code []} where the element sits
     * ({@code Sample.rows[].email}), and through a map's values the same: which key a value sits
     * under is not something a type can say.
     */
    public static List<Finding> scan(Type type, String path) {
        List<Finding> findings = new ArrayList<>();
        walk(type, Map.of(), path, new HashSet<>(), findings, true);
        return findings;
    }

    public static boolean isProtectedName(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return PROTECTED_TERMS.stream().anyMatch(normalized::contains);
    }

    private static void walk(
            Type type,
            Map<TypeVariable<?>, Type> bindings,
            String path,
            Set<Type> ancestors,
            List<Finding> findings,
            boolean root) {
        Type resolved = resolve(type, bindings);

        if (resolved instanceof Class<?> raw) {
            if (raw.isArray()) {
                walk(raw.getComponentType(), bindings, path + "[]", ancestors, findings, false);
                return;
            }
            walkRecordOrDeclared(raw, resolved, Map.of(), path, ancestors, findings, root);
        } else if (resolved instanceof GenericArrayType array) {
            walk(array.getGenericComponentType(), bindings, path + "[]", ancestors, findings, false);
        } else if (resolved instanceof ParameterizedType parameterized) {
            Class<?> raw = (Class<?>) parameterized.getRawType();
            if (raw.isRecord()) {
                walkRecordOrDeclared(
                        raw, resolved, bindingsOf(parameterized, bindings), path, ancestors, findings, root);
            } else if (holdsElements(raw)) {
                for (Type argument : parameterized.getActualTypeArguments()) {
                    walk(argument, bindings, path + "[]", ancestors, findings, false);
                }
            }
        } else if (resolved instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) {
                walk(bound, bindings, path, ancestors, findings, root);
            }
        } else if (resolved instanceof TypeVariable<?> variable) {
            // A parameter nobody bound: only its declared bound can be read, and Object says nothing.
            for (Type bound : variable.getBounds()) {
                if (bound != Object.class) {
                    walk(bound, bindings, path, ancestors, findings, root);
                }
            }
        }
    }

    private static void walkRecordOrDeclared(
            Class<?> raw,
            Type resolved,
            Map<TypeVariable<?>, Type> bindings,
            String path,
            Set<Type> ancestors,
            List<Finding> findings,
            boolean root) {
        // A declaration on the type classifies every place it sits. The root is the exception it
        // always was: a caller scanning a type asks what is inside it, not whether it is itself
        // marked, and a marked root would otherwise report itself and hide its own components.
        Classified onType = raw.getAnnotation(Classified.class);
        if (onType != null && !root) {
            if (onType.value().requiresEncryption()) {
                findings.add(new Finding(path, onType.value(), Source.DECLARED));
            }
            return;
        }
        // Only an ancestor stops the walk (a record that contains itself), not a type reached
        // twice: two addresses on one record are two paths, and a reviewer reads paths.
        if (!raw.isRecord() || !ancestors.add(resolved)) {
            return;
        }
        for (RecordComponent component : raw.getRecordComponents()) {
            String componentPath = path + "." + component.getName();

            Classified declared = component.getAnnotation(Classified.class);
            if (declared == null) {
                // A reviewed declaration on the component's own type, or on what a collection of
                // it holds, beats a guess from the component's name.
                declared = declaredOnType(component.getGenericType(), bindings);
            }
            if (declared != null) {
                if (declared.value().requiresEncryption()) {
                    findings.add(new Finding(componentPath, declared.value(), Source.DECLARED));
                }
                continue;
            }
            if (isProtectedName(component.getName())) {
                findings.add(new Finding(componentPath, DataClass.PERSONAL, Source.NAME_HEURISTIC));
            }
            walk(component.getGenericType(), bindings, componentPath, ancestors, findings, false);
        }
        ancestors.remove(resolved);
    }

    private static @Nullable Classified declaredOnType(Type type, Map<TypeVariable<?>, Type> bindings) {
        Type resolved = resolve(type, bindings);
        if (resolved instanceof Class<?> raw) {
            return raw.isArray()
                    ? declaredOnType(raw.getComponentType(), bindings)
                    : raw.getAnnotation(Classified.class);
        }
        if (resolved instanceof GenericArrayType array) {
            return declaredOnType(array.getGenericComponentType(), bindings);
        }
        if (resolved instanceof ParameterizedType parameterized) {
            Class<?> raw = (Class<?>) parameterized.getRawType();
            Classified direct = raw.getAnnotation(Classified.class);
            if (direct != null || !holdsElements(raw)) {
                return direct;
            }
            for (Type argument : parameterized.getActualTypeArguments()) {
                Classified inner = declaredOnType(argument, bindings);
                if (inner != null) {
                    return inner;
                }
            }
        }
        return null;
    }

    /** The containers whose type arguments are what they hold; everything else is read as a record or ignored. */
    private static boolean holdsElements(Class<?> raw) {
        return Iterable.class.isAssignableFrom(raw)
                || Map.class.isAssignableFrom(raw)
                || Optional.class.isAssignableFrom(raw)
                || Stream.class.isAssignableFrom(raw);
    }

    private static Map<TypeVariable<?>, Type> bindingsOf(
            ParameterizedType parameterized, Map<TypeVariable<?>, Type> enclosing) {
        TypeVariable<?>[] parameters = ((Class<?>) parameterized.getRawType()).getTypeParameters();
        Type[] arguments = parameterized.getActualTypeArguments();
        Map<TypeVariable<?>, Type> bindings = new HashMap<>();
        for (int i = 0; i < parameters.length && i < arguments.length; i++) {
            bindings.put(parameters[i], resolve(arguments[i], enclosing));
        }
        return bindings;
    }

    /** A type variable replaced by what it was bound to, however many levels up that was. */
    private static Type resolve(Type type, Map<TypeVariable<?>, Type> bindings) {
        Type current = type;
        for (int hops = 0; current instanceof TypeVariable<?> variable && hops < 8; hops++) {
            Type bound = bindings.get(variable);
            if (bound == null) {
                return current;
            }
            current = bound;
        }
        return current;
    }

    /** Where a classification came from, so a reviewer can tell a guess from a declaration. */
    public enum Source {
        DECLARED,
        NAME_HEURISTIC
    }

    public record Finding(String path, DataClass dataClass, Source source) {

        @Override
        public String toString() {
            return "%s (%s, %s)".formatted(path, dataClass, source);
        }
    }
}
