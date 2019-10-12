package io.synlabs.synvision.views;


import io.synlabs.synvision.util.LongObfuscator;

public interface Response {
    default Long mask(final Long number) {
        return number != null ? LongObfuscator.INSTANCE.obfuscate(number) : null;
    }
}
