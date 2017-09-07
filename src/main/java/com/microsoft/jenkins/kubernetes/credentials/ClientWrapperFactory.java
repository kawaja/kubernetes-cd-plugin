/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.kubernetes.credentials;

import com.microsoft.jenkins.kubernetes.KubernetesClientWrapper;
import hudson.FilePath;

import java.io.Serializable;

/**
 * The serializable factory that produces a {@link KubernetesClientWrapper}.
 * <p>
 * The implementation should be serializable. It will be passed to the remote node when required, and build the
 * client wrapper there.
 */
public interface ClientWrapperFactory extends Serializable {
    KubernetesClientWrapper buildClient(FilePath workspace) throws Exception;

    /**
     * The builder that builds {@link ClientWrapperFactory}.
     */
    interface Builder {
        ClientWrapperFactory buildClientWrapperFactory();
    }
}
