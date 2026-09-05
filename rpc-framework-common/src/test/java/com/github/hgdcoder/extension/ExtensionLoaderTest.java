package com.github.hgdcoder.extension;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 SPI 映射校验、单例缓存和带参新实例语义。 */
public class ExtensionLoaderTest {
    @Test
    void getExtensionShouldCacheNoArgInstance() {
        ExtensionLoader<ValidExtension> loader =
                ExtensionLoader.getExtensionLoader(ValidExtension.class);

        ValidExtension first = loader.getExtension("valid");
        ValidExtension second = loader.getExtension("valid");

        assertSame(first, second);
        assertEquals("default", first.value());
    }

    @Test
    void newExtensionShouldCreateIndependentInstancesWithExplicitConstructor() {
        ExtensionLoader<ValidExtension> loader =
                ExtensionLoader.getExtensionLoader(ValidExtension.class);

        ValidExtension first = loader.newExtension(
                "valid",
                new Class<?>[]{String.class},
                "first"
        );
        ValidExtension second = loader.newExtension(
                "valid",
                new Class<?>[]{String.class},
                "second"
        );

        assertNotSame(first, second);
        assertEquals("first", first.value());
        assertEquals("second", second.value());
    }

    @Test
    void newExtensionShouldValidateConstructorContract() {
        ExtensionLoader<ValidExtension> loader =
                ExtensionLoader.getExtensionLoader(ValidExtension.class);

        IllegalArgumentException wrongArgument = assertThrows(
                IllegalArgumentException.class,
                () -> loader.newExtension(
                        "valid",
                        new Class<?>[]{String.class},
                        Integer.valueOf(1)
                )
        );
        assertTrue(wrongArgument.getMessage().contains("index 0"));

        IllegalArgumentException missingConstructor = assertThrows(
                IllegalArgumentException.class,
                () -> loader.newExtension(
                        "valid",
                        new Class<?>[]{Integer.class},
                        Integer.valueOf(1)
                )
        );
        assertTrue(missingConstructor.getMessage().contains("Public constructor"));
    }

    @Test
    void malformedMappingShouldFailFast() {
        assertMappingFailure(
                MalformedExtension.class,
                "expected exactly 'name=implementationClass'"
        );
    }

    @Test
    void missingImplementationClassShouldFailFast() {
        assertMappingFailure(MissingClassExtension.class, "class not found");
    }

    @Test
    void incompatibleImplementationShouldFailFast() {
        assertMappingFailure(WrongTypeExtension.class, "is not assignable");
    }

    @Test
    void conflictingNameShouldFailFast() {
        assertMappingFailure(ConflictingExtension.class, "conflicting extension name");
    }

    private <S> void assertMappingFailure(Class<S> extensionType, String expectedMessage) {
        ExtensionLoader<S> loader = ExtensionLoader.getExtensionLoader(extensionType);
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> loader.getExtension("test")
        );
        assertTrue(error.getMessage().contains(expectedMessage), error.getMessage());
    }

    @SPI
    public interface ValidExtension {
        String value();
    }

    public static final class ValidExtensionImpl implements ValidExtension {
        private final String value;

        public ValidExtensionImpl() {
            this("default");
        }

        public ValidExtensionImpl(String value) {
            this.value = value;
        }

        @Override
        public String value() {
            return value;
        }
    }

    @SPI
    public interface MalformedExtension {
    }

    @SPI
    public interface MissingClassExtension {
    }

    @SPI
    public interface WrongTypeExtension {
    }

    @SPI
    public interface ConflictingExtension {
    }

    public static final class FirstConflictExtension
            implements ConflictingExtension {
    }

    public static final class SecondConflictExtension
            implements ConflictingExtension {
    }
}
