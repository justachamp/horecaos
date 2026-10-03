package uz.horecaos.platform.web.idempotency;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.protection.Classified;
import uz.horecaos.platform.iam.api.protection.ClassificationScanner;
import uz.horecaos.platform.web.authorization.RequiresCapability;

class TmpScannerDiffTests {

    @Test
    void diff() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> out = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("uz.horecaos.platform")) {
            Class<?> controller = Class.forName(definition.getBeanClassName());
            for (Method m : controller.getDeclaredMethods()) {
                boolean idem = m.getAnnotation(uz.horecaos.platform.web.idempotency.Idempotent.class) != null;
                RequiresCapability rc = m.getAnnotation(RequiresCapability.class);
                if (!idem && !(rc != null && rc.mutating())) continue;
                java.lang.reflect.Type type = ResponseBodyProtection.scanTypeOf(m.getGenericReturnType());
                if (type == null) continue;
                Class<?> raw = ResponseBodyProtection.responseTypeOf(m.getGenericReturnType());
                List<String> oldF = new ArrayList<>();
                oldScan(raw, raw.getSimpleName(), new LinkedHashSet<>(), oldF);
                List<String> newF = ClassificationScanner.scan(type, raw.getSimpleName()).stream()
                        .map(Object::toString).toList();
                Set<String> added = new LinkedHashSet<>(newF);
                added.removeAll(oldF);
                Set<String> removed = new LinkedHashSet<>(oldF);
                removed.removeAll(newF);
                if (!added.isEmpty() || !removed.isEmpty()) {
                    out.add(controller.getSimpleName() + "#" + m.getName() + " [" + m.getGenericReturnType().getTypeName() + "]\n   +" + added + "\n   -" + removed);
                }
            }
        }
        Files.writeString(Path.of(System.getProperty("tmp.diff.out", "/tmp/scanner-diff.txt")), String.join("\n", out) + "\ncount=" + out.size() + "\n");
    }

    static void oldScan(Class<?> type, String path, Set<Class<?>> visited, List<String> findings) {
        if (type == null || !type.isRecord() || !visited.add(type)) return;
        for (RecordComponent c : type.getRecordComponents()) {
            String p = path + "." + c.getName();
            Classified declared = c.getAnnotation(Classified.class);
            if (declared == null) declared = c.getType().getAnnotation(Classified.class);
            if (declared != null) {
                if (declared.value().requiresEncryption()) findings.add(p + " (" + declared.value() + ", DECLARED)");
                continue;
            }
            if (ClassificationScanner.isProtectedName(c.getName())) findings.add(p + " (PERSONAL, NAME_HEURISTIC)");
            oldScan(c.getType(), p, visited, findings);
        }
    }
}
