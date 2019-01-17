/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.kubernetes.command;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.microsoft.jenkins.azurecommons.command.CommandState;
import com.microsoft.jenkins.azurecommons.command.IBaseCommandData;
import com.microsoft.jenkins.azurecommons.command.ICommand;
import com.microsoft.jenkins.kubernetes.CustomerTiller;
import com.microsoft.jenkins.kubernetes.HelmContext;
import com.microsoft.jenkins.kubernetes.credentials.KubeconfigCredentials;
import hapi.chart.ChartOuterClass;
import hapi.release.ReleaseOuterClass;
import hapi.release.StatusOuterClass;
import hapi.services.tiller.Tiller.InstallReleaseRequest;
import hapi.services.tiller.Tiller.ListReleasesRequest;
import hapi.services.tiller.Tiller.ListReleasesResponse;
import hapi.services.tiller.Tiller.UpdateReleaseRequest;
import hapi.services.tiller.Tiller.UpdateReleaseResponse;
import hapi.services.tiller.Tiller.InstallReleaseResponse;
import hudson.model.Item;
import hudson.security.ACL;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import org.apache.commons.lang3.StringUtils;
import org.microbean.helm.ReleaseManager;
import org.microbean.helm.Tiller;
import org.microbean.helm.chart.DirectoryChartLoader;
import org.microbean.helm.chart.URLChartLoader;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class HelmDeploymentCommand implements ICommand<HelmDeploymentCommand.IHelmDeploymentData> {

    @Override
    public void execute(IHelmDeploymentData context) {
        HelmContext helmContext = context.getHelmContext();

        // helm chart
        String helmChartLocation = helmContext.getChartLocation();
        helmChartLocation = context.getJobContext().getWorkspace().child(helmChartLocation).getRemote();

        File file = new File(helmChartLocation);
        if (!file.exists()) {
            context.logError(String.format("cannot find helm chart at %s", file.getAbsolutePath()));
            return;
        }
        URI uri = file.toURI();
        ChartOuterClass.Chart.Builder chart = null;
        try (final DirectoryChartLoader chartLoader = new DirectoryChartLoader()) {
            Path path = Paths.get(uri);
            chart = chartLoader.load(path);
        } catch (IOException e) {
            // TODO locating charts fails
            context.logError(e);
        }

        String tillerNamespace = helmContext.getTillerNamespace();

        String kubeConfig = getKubeConfigContent(context.getKubeconfigId(), context.getJobContext().getOwner());

        try (final DefaultKubernetesClient client = new DefaultKubernetesClient(Config.fromKubeconfig(kubeConfig));
             final Tiller tiller = new CustomerTiller(client, tillerNamespace);
             final ReleaseManager releaseManager = new ReleaseManager(tiller)) {

            context.logStatus(helmChartLocation);

            createOrUpdateHelm(releaseManager, helmContext, chart);

            context.setCommandState(CommandState.Success);
        } catch (IOException e) {
            context.logError(e);
            context.setCommandState(CommandState.HasError);
        }
    }

    private String getKubeConfigContent(String configId, Item owner) {
        if (StringUtils.isNotBlank(configId)) {
            final KubeconfigCredentials credentials = CredentialsMatchers.firstOrNull(
                    CredentialsProvider.lookupCredentials(
                            KubeconfigCredentials.class,
                            owner,
                            ACL.SYSTEM,
                            Collections.<DomainRequirement>emptyList()),
                    CredentialsMatchers.withId(configId));
            if (credentials == null) {
                throw new IllegalArgumentException("Cannot find kubeconfig credentials with id " + configId);
            }
            return credentials.getContent();
        }
        return null;
    }


    private void createOrUpdateHelm(ReleaseManager releaseManager, HelmContext helmContext,
                                    ChartOuterClass.Chart.Builder chart) {
        if (isHelmReleaseExist(releaseManager, helmContext)) {
            updateHelmRelease(releaseManager, helmContext, chart);
        } else {
            installHelmRelease(releaseManager, helmContext, chart);
        }
    }

    private boolean isHelmReleaseExist(ReleaseManager releaseManager, HelmContext helmContext) {
        ListReleasesRequest.Builder builder = ListReleasesRequest.newBuilder();
        builder.setFilter(helmContext.getReleaseName());
        builder.addAllStatusCodes(Arrays.asList(StatusOuterClass.Status.Code.FAILED,
                StatusOuterClass.Status.Code.DEPLOYED));
        Iterator<ListReleasesResponse> responses = releaseManager.list(builder.build());
        if (responses.hasNext()) {
            ListReleasesResponse releasesResponse = responses.next();
            ReleaseOuterClass.Release release = releasesResponse.getReleases(0);
            String releaseNamespace = release.getNamespace();
            if (!helmContext.getReleaseName().equals(releaseNamespace)) {
                //TODO release name has been used in other namespace
                System.out.println(releaseNamespace);
            }
            return true;
        }
        return false;
    }

    private void installHelmRelease(ReleaseManager releaseManager, HelmContext helmContext,
                                    ChartOuterClass.Chart.Builder chart) {
        final InstallReleaseRequest.Builder requestBuilder = InstallReleaseRequest.newBuilder();
        requestBuilder.setNamespace(helmContext.getTargetNamespace());
        requestBuilder.setTimeout(helmContext.getTimeout());
        requestBuilder.setName(helmContext.getReleaseName());
        requestBuilder.setWait(helmContext.isWait()); // Wait for Pods to be ready

        try {
            Future<InstallReleaseResponse> install =
                    releaseManager.install(requestBuilder, chart);
            InstallReleaseResponse installReleaseResponse = install.get();
            ReleaseOuterClass.Release release = installReleaseResponse.getRelease();
            assert release != null;
        } catch (IOException | InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }
    }

    private void updateHelmRelease(ReleaseManager releaseManager, HelmContext helmContext,
                                   ChartOuterClass.Chart.Builder chart) {
        UpdateReleaseRequest.Builder builder = UpdateReleaseRequest.newBuilder();

        builder.setName(helmContext.getReleaseName());
        builder.setTimeout(helmContext.getTimeout());
//        builder.setRecreate(true);
//        builder.setForce(true);
        builder.setWait(helmContext.isWait());

        try {
            Future<UpdateReleaseResponse> update =
                    releaseManager.update(builder, chart);
            UpdateReleaseResponse updateReleaseResponse = update.get();
            ReleaseOuterClass.Release release = updateReleaseResponse.getRelease();
            assert release != null;
        } catch (IOException | InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }
    }

    public interface IHelmDeploymentData extends IBaseCommandData {
        String getKubeconfigId();

        HelmContext getHelmContext();
    }
}