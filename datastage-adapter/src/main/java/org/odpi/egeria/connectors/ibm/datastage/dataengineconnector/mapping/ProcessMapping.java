/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright Contributors to the ODPi Egeria project. */
package org.odpi.egeria.connectors.ibm.datastage.dataengineconnector.mapping;

import org.odpi.egeria.connectors.ibm.datastage.dataengineconnector.model.DataStageCache;
import org.odpi.egeria.connectors.ibm.datastage.dataengineconnector.model.DataStageJob;
import org.odpi.egeria.connectors.ibm.igc.clientlibrary.model.base.*;
import org.odpi.egeria.connectors.ibm.igc.clientlibrary.model.common.ItemList;
import org.odpi.openmetadata.accessservices.dataengine.model.*;
import org.odpi.openmetadata.accessservices.dataengine.model.Process;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Mappings for creating a Process.
 */
public class ProcessMapping extends BaseMapping {

    private static final Logger log = LoggerFactory.getLogger(ProcessMapping.class);
    private Process process;

    /**
     * Create a new process from a DataStageJob that represents either a job or a sequence.
     *
     * @param cache used by this mapping
     * @param job the job or sequence from which to create a process
     */
    public ProcessMapping(DataStageCache cache, DataStageJob job) {
        super(cache);
        process = null;
        if (job.getType().equals(DataStageJob.JobType.SEQUENCE)) {
            Dsjob jobObj = job.getJobObject();
            process = getSkeletonProcess(jobObj);
            if (process != null) {
                PortAliasMapping portAliasMapping = new PortAliasMapping(cache, job);
                process.setPortAliases(portAliasMapping.getPortAliases());
            }
        } else {
            Dsjob jobObj = job.getJobObject();
            process = getSkeletonProcess(jobObj);
            if (process != null) {
                PortAliasMapping inputAliasMapping = new PortAliasMapping(cache, job, job.getInputStages(), PortType.INPUT_PORT);
                PortAliasMapping outputAliasMapping = new PortAliasMapping(cache, job, job.getOutputStages(), PortType.OUTPUT_PORT);
                process.setPortAliases(Stream.concat(inputAliasMapping.getPortAliases().stream(), outputAliasMapping.getPortAliases().stream()).collect(Collectors.toList()));
                Set<LineageMapping> lineageMappings = new HashSet<>();
                for (Link link : job.getAllLinks()) {
                    LineageMappingMapping lineageMappingMapping = new LineageMappingMapping(cache, job, link);
                    Set<LineageMapping> crossStageLineageMappings = lineageMappingMapping.getLineageMappings();
                    if (crossStageLineageMappings != null && !crossStageLineageMappings.isEmpty()) {
                        lineageMappings.addAll(crossStageLineageMappings);
                    }
                }
                if (!lineageMappings.isEmpty()) {
                    process.setLineageMappings(new ArrayList<>(lineageMappings));
                }
                ItemList<SequenceJob> sequencedBy = job.getJobObject().getSequencedByJobs();
                if (sequencedBy != null) {
                    // Setup a parent process relationship to any sequences that happen to call this job
                    // as only APPEND parents (not OWNED), since removal of the sequence does not result in removal
                    // of the job itself
                    List<ParentProcess> parents = new ArrayList<>();
                    List<SequenceJob> allSequences = igcRestClient.getAllPages("sequenced_by_jobs", sequencedBy);
                    for (SequenceJob sequenceJob : allSequences) {
                        ParentProcess parent = new ParentProcess();
                        String sequenceJobQN = getFullyQualifiedName(sequenceJob);
                        if (sequenceJobQN != null) {
                            parent.setQualifiedName(sequenceJobQN);
                            parent.setProcessContainmentType(ProcessContainmentType.APPEND);
                            parents.add(parent);
                        } else {
                            log.error("Unable to determine identity for sequence -- not including: {}", sequenceJob);
                        }
                    }
                    if (!parents.isEmpty()) {
                        process.setParentProcesses(parents);
                    }
                }
            }
        }
    }

    /**
     * Create a new process from the provided DataStage stage.
     *
     * @param cache used by this mapping
     * @param job the job within which the stage exists
     * @param stage the stage from which to create a process
     */
    public ProcessMapping(DataStageCache cache, DataStageJob job, Stage stage) {
        super(cache);
        process = getSkeletonProcess(stage);
        if (process != null) {
            Set<PortImplementation> portImplementations = new HashSet<>();
            Set<LineageMapping> lineageMappings = new HashSet<>();
            log.debug("Adding input links: {}", stage.getInputLinks());
            addImplementationDetails(job, stage, "input_links", stage.getInputLinks(), PortType.INPUT_PORT, portImplementations, lineageMappings);
            addDataStoreDetails(job, stage, "reads_from_(design)", stage.getReadsFromDesign(), PortType.INPUT_PORT, portImplementations, lineageMappings);
            log.debug("Adding output links: {}", stage.getOutputLinks());
            addImplementationDetails(job, stage, "output_links", stage.getOutputLinks(), PortType.OUTPUT_PORT, portImplementations, lineageMappings);
            addDataStoreDetails(job, stage, "writes_to_(design)", stage.getWritesToDesign(), PortType.OUTPUT_PORT, portImplementations, lineageMappings);
            process.setPortImplementations(new ArrayList<>(portImplementations));
            process.setLineageMappings(new ArrayList<>(lineageMappings));
            // Stages are owned by the job that contains them, so setup an owned parent process relationship to the
            // job-level
            List<ParentProcess> parents = new ArrayList<>();
            ParentProcess parent = new ParentProcess();
            String jobQN = getFullyQualifiedName(job.getJobObject());
            if (jobQN != null) {
                parent.setQualifiedName(jobQN);
                parent.setProcessContainmentType(ProcessContainmentType.OWNED);
                parents.add(parent);
                process.setParentProcesses(parents);
            } else {
                log.error("Unable to determine identity for job -- not including: {}", job.getJobObject());
            }
        }
    }

    /**
     * Retrieve the mapped process.
     *
     * @return Process
     */
    public Process getProcess() { return this.process; }

    /**
     * Construct a minimal Process object from the provided IGC object (stage, job, sequence, etc).
     *
     * @param igcObj the IGC object from which to construct the skeletal Process
     * @return Process
     */
    private Process getSkeletonProcess(InformationAsset igcObj) {
        Process process = null;
        if (igcObj != null) {
            process = new Process();
            process.setName(igcObj.getName());
            process.setDisplayName(igcObj.getName());
            String objQN = getFullyQualifiedName(igcObj);
            if (objQN != null) {
                log.debug("Constructing process for: {}", objQN);
                process.setQualifiedName(objQN);
                process.setDescription(getDescription(igcObj));
                process.setOwner(igcObj.getCreatedBy());
                // TODO: setAdditionalProperties or setExtendedProperties with other information on the IGC object?
            } else {
                log.error("Unable to determine identity for asset -- not including: {}", igcObj);
                process = null;
            }
        }
        return process;
    }

    /**
     * Add implementation details of the job (ports and lineage mappings) to the provided lists, for the provided stage.
     *
     * @param job the job within which the stage exists
     * @param stage the stage for which to add implementation details
     * @param propertyName the name of the property from which links were retrieved
     * @param links the links of the stage from which to draw implementation details
     * @param portType the type of port
     * @param portImplementations the list of PortImplementations to append to with implementation details
     * @param lineageMappings the list of LineageMappings to append to with implementation details
     */
    private void addImplementationDetails(DataStageJob job,
                                          Stage stage,
                                          String propertyName,
                                          ItemList<Link> links,
                                          PortType portType,
                                          Set<PortImplementation> portImplementations,
                                          Set<LineageMapping> lineageMappings) {
        String stageQN = getFullyQualifiedName(stage);
        // Setup an x_PORT for each x_link into / out of the stage
        List<Link> allLinks = igcRestClient.getAllPages(propertyName, links);
        for (Link linkRef : allLinks) {
            Link linkObjFull = job.getLinkByRid(linkRef.getId());
            log.debug("Adding implementation details for link: {}", linkObjFull);
            PortImplementationMapping portImplementationMapping = new PortImplementationMapping(cache, job, linkObjFull, portType, stageQN);
            portImplementations.add(portImplementationMapping.getPortImplementation());
            LineageMappingMapping lineageMappingMapping = new LineageMappingMapping(cache, job, linkObjFull, stageQN, portType == PortType.INPUT_PORT);
            lineageMappings.addAll(lineageMappingMapping.getLineageMappings());
        }
    }

    /**
     * Add implementation details of the job (ports and lineage mappings) to the provided lists, for any data stores
     * used by the specified stage.
     *
     * @param job the job within which the stage exists
     * @param stage the stage for which to add implementation details
     * @param propertyName the name of the property from which stores were retrieved
     * @param stores the stores for which to create the implementation details
     * @param portType the type of port
     * @param portImplementations the list of PortImplementations to append to with implementation details
     * @param lineageMappings the list of LineageMappings to append to with implementation details
     */
    private void addDataStoreDetails(DataStageJob job,
                                     Stage stage,
                                     String propertyName,
                                     ItemList<InformationAsset> stores,
                                     PortType portType,
                                     Set<PortImplementation> portImplementations,
                                     Set<LineageMapping> lineageMappings) {
        // Setup an x_PORT for any data stores that are used by design as sources / targets
        String fullyQualifiedStageName = getFullyQualifiedName(stage);
        if (fullyQualifiedStageName != null) {
            List<InformationAsset> allStores = igcRestClient.getAllPages(propertyName, stores);
            for (InformationAsset storeRef : allStores) {
                List<Classificationenabledgroup> fieldsForStore = cache.getFieldsForStore(storeRef);
                PortImplementationMapping portImplementationMapping = new PortImplementationMapping(cache, stage, portType, fieldsForStore, fullyQualifiedStageName);
                portImplementations.add(portImplementationMapping.getPortImplementation());
                LineageMappingMapping lineageMappingMapping = new LineageMappingMapping(cache, job, fieldsForStore, portType.equals(PortType.INPUT_PORT), fullyQualifiedStageName);
                lineageMappings.addAll(lineageMappingMapping.getLineageMappings());
            }
        } else {
            log.error("Unable to determine identity for stage -- not including: {}", stage);
        }
    }

}
