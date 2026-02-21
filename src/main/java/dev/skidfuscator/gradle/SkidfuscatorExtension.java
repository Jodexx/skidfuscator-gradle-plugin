package dev.skidfuscator.gradle;

import lombok.Getter;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

import javax.inject.Inject;

public class SkidfuscatorExtension {

    @Getter
    private final ListProperty<String> exempt;
    @Getter
    private final ListProperty<String> exclude;
    @Getter
    private final ListProperty<String> libs;

    private final TransformersExtension transformersExtension;

    @Getter
    private final Property<Boolean> phantom;
    @Getter
    private final Property<Boolean> fuckit;
    @Getter
    private final Property<Boolean> debug;
    @Getter
    private final Property<Boolean> notrack;
    @Getter
    private final Property<String> runtime;

    @Getter
    private final Property<String> input;
    @Getter
    private final Property<String> output;
    @Getter
    private final Property<String> configFileName;
    @Getter
    private final Property<String> skidfuscatorVersion;

    @Inject
    public SkidfuscatorExtension(ObjectFactory objects, NamedDomainObjectContainer<TransformerSpec> transformersContainer) {
        this.exempt = objects.listProperty(String.class);
        this.exclude = objects.listProperty(String.class);
        this.libs = objects.listProperty(String.class);

        this.phantom = objects.property(Boolean.class);
        this.fuckit = objects.property(Boolean.class);
        this.debug = objects.property(Boolean.class);
        this.notrack = objects.property(Boolean.class);
        this.runtime = objects.property(String.class);
        this.input = objects.property(String.class);
        this.output = objects.property(String.class);
        this.configFileName = objects.property(String.class);
        this.skidfuscatorVersion = objects.property(String.class);

        // Встановлюємо значення за замовчуванням
        this.phantom.convention(false);
        this.fuckit.convention(false);
        this.debug.convention(false);
        this.notrack.convention(false);
        this.configFileName.convention("skidfuscator.conf");
        this.skidfuscatorVersion.convention("latest");

        this.transformersExtension = new TransformersExtension(transformersContainer);
    }

    public TransformersExtension getTransformers() { return transformersExtension; }

    public void transformers(org.gradle.api.Action<? super NamedDomainObjectContainer<TransformerSpec>> action) {
        this.transformersExtension.transformers(action);
    }
}