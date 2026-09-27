package com.github.guoyyit.modelregistryjava.tests;

import com.github.guoyyit.modelregistryjava.config.RegistryProperties;
import com.github.guoyyit.modelregistryjava.service.ModelRegistryService;

import java.nio.file.Path;

/** Builds service instances backed by isolated temporary directories. */
final class TestServices {

    private TestServices() {
    }

    static ModelRegistryService newService(Path home) {
        return newService(home, RegistryProperties.DuplicateContent.REUSE_PUBLISHED);
    }

    static ModelRegistryService newService(Path home,
                                           RegistryProperties.DuplicateContent mode) {
        RegistryProperties props = new RegistryProperties();
        props.setHome(home.toString());
        props.setRecoverOnStartup(true);
        props.setDuplicateContent(mode);
        ModelRegistryService service = new ModelRegistryService(props);
        service.recover();
        return service;
    }
}
