/*******************************************************************************
 * Copyright (c) 2019 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package com.redhat.devtools.lsp4ij.client;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.redhat.devtools.lsp4ij.LSPIJUtils;
import com.redhat.devtools.lsp4ij.LanguageServerWrapper;
import com.redhat.devtools.lsp4ij.ServerMessageHandler;
import com.redhat.devtools.lsp4ij.features.diagnostics.LSPDiagnosticHandler;
import com.redhat.devtools.lsp4ij.features.progress.LSPProgressManager;
import com.redhat.devtools.lsp4ij.internal.InlayHintsFactoryBridge;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * LSP {@link LanguageClient} implementation for IntelliJ.
 */
public class LanguageClientImpl implements LanguageClient, Disposable {
    private final Project project;
    private Consumer<PublishDiagnosticsParams> diagnosticHandler;

    private LanguageServer server;
    private LanguageServerWrapper wrapper;

    private boolean disposed;

    private Runnable didChangeConfigurationListener;

    @NotNull
    private final LSPProgressManager progressManager;

    public LanguageClientImpl(Project project) {
        this.project = project;
        progressManager = new LSPProgressManager();
    }

    public Project getProject() {
        return project;
    }

    public final void connect(LanguageServer server, LanguageServerWrapper wrapper) {
        this.server = server;
        this.wrapper = wrapper;
        this.diagnosticHandler = new LSPDiagnosticHandler(wrapper);
        this.progressManager.connect(server, wrapper);
    }

    protected final LanguageServer getLanguageServer() {
        return server;
    }

    @Override
    public void telemetryEvent(Object object) {
        // TODO
    }

    @Override
    public final CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
        return ServerMessageHandler.showMessageRequest(wrapper, requestParams);
    }

    @Override
    public final void showMessage(MessageParams messageParams) {
        ServerMessageHandler.showMessage(wrapper.getServerDefinition().getDisplayName(), messageParams, getProject());
    }

    @Override
    public CompletableFuture<ShowDocumentResult> showDocument(ShowDocumentParams params) {
        return ServerMessageHandler.showDocument(params, getProject());
    }

    @Override
    public final void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        this.diagnosticHandler.accept(diagnostics);
    }

    @Override
    public final void logMessage(MessageParams message) {
        CompletableFuture.runAsync(() -> ServerMessageHandler.logMessage(wrapper, message));
    }

    @Override
    public final CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams params) {
        CompletableFuture<ApplyWorkspaceEditResponse> future = new CompletableFuture<>();
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            LSPIJUtils.applyWorkspaceEdit(params.getEdit());
            future.complete(new ApplyWorkspaceEditResponse(true));
        });
        return future;
    }

    @Override
    public CompletableFuture<Void> registerCapability(RegistrationParams params) {
        return CompletableFuture.runAsync(() -> wrapper.registerCapability(params));
    }

    @Override
    public CompletableFuture<Void> unregisterCapability(UnregistrationParams params) {
        return CompletableFuture.runAsync(() -> wrapper.unregisterCapability(params));
    }

    @Override
    public CompletableFuture<List<WorkspaceFolder>> workspaceFolders() {
        Project project = wrapper.getProject();
        if (project != null) {
            List<WorkspaceFolder> folders = List.of(LSPIJUtils.toWorkspaceFolder(project));
            return CompletableFuture.completedFuture(folders);
        }
        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    @Override
    public CompletableFuture<Void> refreshInlayHints() {
        return CompletableFuture.runAsync(() -> {
            if (wrapper == null) {
                return;
            }
            refreshInlayHintsForAllOpenedFiles();
        });
    }

    private void refreshInlayHintsForAllOpenedFiles() {
        for (var fileData : wrapper.getConnectedFiles()) {
            VirtualFile file = fileData.getFile();
            final PsiFile psiFile = LSPIJUtils.getPsiFile(file, project);
            if (psiFile != null) {
                Editor[] editors = LSPIJUtils.editorsForFile(file, getProject());
                InlayHintsFactoryBridge.refreshInlayHints(psiFile, editors, true);
            }
        }
    }

    @Override
    public CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams params) {
        return progressManager.createProgress(params);
    }

    @Override
    public void notifyProgress(ProgressParams params) {
        progressManager.notifyProgress(params);
    }

    @Override
    public void dispose() {
        this.disposed = true;
        this.progressManager.dispose();
    }

    public boolean isDisposed() {
        return disposed || getProject().isDisposed();
    }

    protected Object createSettings() {
        return null;
    }

    protected synchronized Runnable getDidChangeConfigurationListener() {
        if (didChangeConfigurationListener != null) {
            return didChangeConfigurationListener;
        }
        didChangeConfigurationListener = this::triggerChangeConfiguration;
        return didChangeConfigurationListener;
    }

    protected void triggerChangeConfiguration() {
        LanguageServer languageServer = getLanguageServer();
        if (languageServer == null) {
            return;
        }
        Object settings = createSettings();
        if (settings == null) {
            return;
        }
        DidChangeConfigurationParams params = new DidChangeConfigurationParams(settings);
        languageServer.getWorkspaceService().didChangeConfiguration(params);
    }

}
