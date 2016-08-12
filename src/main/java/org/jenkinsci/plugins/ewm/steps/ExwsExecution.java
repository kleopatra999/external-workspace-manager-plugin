package org.jenkinsci.plugins.ewm.steps;

import com.google.inject.Inject;
import hudson.AbortException;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.slaves.WorkspaceList;
import hudson.util.DescribableList;
import org.jenkinsci.plugins.ewm.definitions.Template;
import org.jenkinsci.plugins.ewm.nodes.DiskNode;
import org.jenkinsci.plugins.ewm.nodes.DiskPoolNode;
import org.jenkinsci.plugins.ewm.nodes.ExternalWorkspaceProperty;
import org.jenkinsci.plugins.ewm.steps.model.ExternalWorkspace;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.BodyExecution;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.List;

import static java.lang.String.format;

/**
 * The execution of {@link ExwsStep}.
 *
 * @author Alexandru Somai
 */
public class ExwsExecution extends AbstractStepExecutionImpl {

    private static final long serialVersionUID = 1L;

    @Inject(optional = true)
    private transient ExwsStep step;

    @StepContextParameter
    private transient Computer computer;
    @StepContextParameter
    private transient TaskListener listener;
    private BodyExecution body;

    @Override
    public boolean start() throws Exception {
        ExternalWorkspace exws = step.getExternalWorkspace();
        if (exws == null) {
            throw new AbortException("No external workspace provided. Did you run the exwsAllocate step?");
        }

        Node node = computer.getNode();
        if (node == null) {
            throw new Exception("The node is not live due to some unexpected conditions: the node might have been taken offline, or may have been removed");
        }

        listener.getLogger().println("Searching for disk definitions in the External Workspace Templates from Jenkins global config");
        Template template = findTemplate(exws.getDiskPoolId(), node.getLabelString(), step.getDescriptor().getTemplates());
        List<DiskNode> diskNodes;
        if (template != null) {
            diskNodes = template.getDiskNodes();
        } else {
            // fallback to finding the disk definitions into the node config
            listener.getLogger().println("Searching for disk definitions in the Node config");
            ExternalWorkspaceProperty exwsNodeProperty = findNodeProperty(node);
            DiskPoolNode diskPoolNode = findDiskPoolNode(exws.getDiskPoolId(), exwsNodeProperty.getDiskPoolNodes(), node.getDisplayName());
            diskNodes = diskPoolNode.getDiskNodes();
        }

        DiskNode diskNode = findDiskNode(exws.getDiskId(), diskNodes, node.getDisplayName());

        FilePath diskFilePath = new FilePath(node.getChannel(), diskNode.getLocalRootPath());
        FilePath baseWorkspace = diskFilePath.child(exws.getPathOnDisk());

        WorkspaceList.Lease lease = computer.getWorkspaceList().allocate(baseWorkspace);
        FilePath workspace = lease.path;
        listener.getLogger().println("Running in " + workspace);
        body = getContext().newBodyInvoker()
                .withContext(workspace)
                .withCallback(new Callback(getContext(), lease))
                .start();
        return false;
    }

    @Override
    public void stop(@Nonnull Throwable cause) throws Exception {
        if (body != null) {
            body.cancel(cause);
        }
    }

    /**
     * Finds a template from the given templates list that matches the given label String.
     *
     * @param diskPoolRefId   the disk pool ref id that the found template should have
     * @param nodeLabelString the label that the template has
     * @param templates       a list of templates
     * @return the template matching the given label, <code>null</code> otherwise
     * @throws IOException if the found template doesn't have defined a disk pool ref id
     *                     if the defined disk pool ref id doesn't match the one given as parameter
     */
    @CheckForNull
    private static Template findTemplate(String diskPoolRefId, String nodeLabelString, List<Template> templates) throws IOException {
        Template selectedTemplate = null;
        for (Template template : templates) {
            String templateLabel = template.getLabel();
            if (templateLabel != null && nodeLabelString.contains(templateLabel)) {
                selectedTemplate = template;
                break;
            }
        }
        if (selectedTemplate == null) {
            return null;
        }

        String templateDiskPoolRefId = selectedTemplate.getDiskPoolRefId();
        if (templateDiskPoolRefId == null) {
            String message = format("In Jenkins global config, the Template labeled '%s' does not have defined a Disk Pool Ref ID", selectedTemplate.getLabel());
            throw new AbortException(message);
        }
        if (!templateDiskPoolRefId.equals(diskPoolRefId)) {
            String message = format("In Jenkins global config, the Template labeled '%s' has defined a wrong Disk Pool Ref ID '%s'. " +
                    "The correct Disk Pool Ref ID should be '%s', as the one used by the exwsAllocate step", selectedTemplate.getLabel(), selectedTemplate.getDiskPoolRefId(), diskPoolRefId);
            throw new AbortException(message);
        }

        return selectedTemplate;
    }

    /**
     * Finds the {@link NodeProperty} for the external workspace definition.
     *
     * @param node the current node
     * @return the node property for the external workspace manager
     * @throws IOException if node property was not found
     */
    @Nonnull
    private static ExternalWorkspaceProperty findNodeProperty(Node node) throws IOException {
        DescribableList<NodeProperty<?>, NodePropertyDescriptor> nodeProperties = node.getNodeProperties();

        ExternalWorkspaceProperty exwsNodeProperty = null;
        for (NodeProperty<?> nodeProperty : nodeProperties) {
            if (nodeProperty instanceof ExternalWorkspaceProperty) {
                exwsNodeProperty = (ExternalWorkspaceProperty) nodeProperty;
                break;
            }
        }

        if (exwsNodeProperty == null) {
            String message = format("There is no External Workspace config defined in Node '%s' config", node.getDisplayName());
            throw new AbortException(message);
        }

        return exwsNodeProperty;
    }

    /**
     * Selects the {@link DiskPoolNode} that has the {@link DiskPoolNode#diskPoolRefId} equal to the given
     * {@code diskPoolRefId} param.
     *
     * @param diskPoolRefId the disk pool reference id to be searching for
     * @param diskPoolNodes the list of disk pools
     * @param nodeName      the name of the current node
     * @return the Disk Pool that has the matching reference id.
     * @throws IOException if no disk pool was found
     */
    @Nonnull
    private static DiskPoolNode findDiskPoolNode(@Nonnull String diskPoolRefId, @Nonnull List<DiskPoolNode> diskPoolNodes,
                                                 @Nonnull String nodeName) throws IOException {
        DiskPoolNode selected = null;
        for (DiskPoolNode diskPoolNode : diskPoolNodes) {
            if (diskPoolRefId.equals(diskPoolNode.getDiskPoolRefId())) {
                selected = diskPoolNode;
                break;
            }
        }

        if (selected == null) {
            String message = format("No Disk Pool Ref ID matching '%s' was found in Node '%s' config", diskPoolRefId, nodeName);
            throw new AbortException(message);
        }

        return selected;
    }

    /**
     * Finds the disk definition from the given disks list that matches the given disk id.
     *
     * @param diskId    the disk id that the node definition should have
     * @param diskNodes the list of available disk definitions
     * @param nodeName  the name of the current node
     * @return the disk definition that matches the given disk id
     * @throws IOException if no disk definition was found
     *                     if the disk definition has its local root path null
     */
    @Nonnull
    private static DiskNode findDiskNode(String diskId, List<DiskNode> diskNodes, String nodeName) throws IOException {
        DiskNode selectedDiskNode = null;
        for (DiskNode diskNode : diskNodes) {
            if (diskId.equals(diskNode.getDiskRefId())) {
                selectedDiskNode = diskNode;
                break;
            }
        }
        if (selectedDiskNode == null) {
            String message = format("The Node '%s' config does not have defined any Disk Ref ID '%s'", nodeName, diskId);
            throw new AbortException(message);
        }
        if (selectedDiskNode.getLocalRootPath() == null) {
            String message = format("The Node '%s' config does not have defined any local root path for Disk Ref ID '%s'", nodeName, diskId);
            throw new AbortException(message);
        }

        return selectedDiskNode;
    }

    private static final class Callback extends BodyExecutionCallback {

        private final StepContext context;
        private final WorkspaceList.Lease lease;

        Callback(StepContext context, WorkspaceList.Lease lease) {
            this.context = context;
            this.lease = lease;
        }

        @Override
        public void onSuccess(StepContext context, Object result) {
            lease.release();
            this.context.onSuccess(result);
        }

        @Override
        public void onFailure(StepContext context, Throwable t) {
            lease.release();
            this.context.onFailure(t);
        }
    }
}
