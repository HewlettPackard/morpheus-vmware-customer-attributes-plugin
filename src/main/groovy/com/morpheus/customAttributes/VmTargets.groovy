package com.morpheus.customAttributes

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Instance
import com.morpheusdata.model.Workload
import groovy.util.logging.Slf4j

/**
 * Resolves the vCenter VMs an action applies to, from the externalId (for example vm-4135) of the
 * Morpheus server records, so a VM is never looked up by name.
 *
 * An instance yields all of its VMs, primary VM first (lowest workload id). A server yields only itself.
 * Every VM must have a valid reference, otherwise nothing is resolved at all.
 */
@Slf4j
final class VmTargets {

    static final int MAX_VMS = 25

    static final class Target {
        final Long serverId
        final String name
        final String vmMor

        Target(Long serverId, String name, String vmMor) {
            this.serverId = serverId
            this.name = name
            this.vmMor = vmMor
        }
    }

    static final class Resolved {
        final Long cloudId
        final List<Target> targets

        Resolved(Long cloudId, List<Target> targets) {
            this.cloudId = cloudId
            this.targets = Collections.unmodifiableList(targets)
        }

        Target getPrimary() { return targets.first() }
    }

    private VmTargets() {}

    /** Throws IllegalStateException with a short public message when the VMs cannot all be resolved. */
    static Resolved forInstance(MorpheusContext morpheus, Instance instance) {
        Long cloudId = instance.provisionZoneId
        if (cloudId == null) {
            throw new IllegalStateException('This instance has no cloud')
        }
        List<Long> serverIds = serverIdsOf(morpheus, instance)
        if (serverIds.isEmpty()) {
            throw new IllegalStateException('This instance has no VM record in Morpheus')
        }
        if (serverIds.size() > MAX_VMS) {
            throw new IllegalStateException("This instance has more than ${MAX_VMS} VMs; refusing to continue".toString())
        }
        List<Target> targets = serverIds.collect { Long id -> toTarget(morpheus, id, cloudId) }
        return new Resolved(cloudId, targets)
    }

    /**
     * Narrows an instance's VMs to the one the user selected, or keeps all of them for 'all'.
     * The target must be a server id of this instance; with several VMs it is required.
     */
    static Resolved select(Resolved resolved, String target) {
        String t = target?.trim()
        if (resolved.targets.size() == 1) {
            if (!t || t == 'all' || t == resolved.primary.serverId.toString()) return resolved
            throw new IllegalStateException('The selected VM does not belong to this instance')
        }
        if (!t) {
            throw new IllegalStateException('Select a VM first')
        }
        if (t == 'all') return resolved
        Target match = resolved.targets.find { Target x -> x.serverId.toString() == t }
        if (match == null) {
            throw new IllegalStateException('The selected VM does not belong to this instance')
        }
        return new Resolved(resolved.cloudId, [match])
    }

    static Resolved forServer(MorpheusContext morpheus, ComputeServer server) {
        Long cloudId = server?.cloud?.id
        if (cloudId == null) {
            throw new IllegalStateException('This server has no cloud')
        }
        return new Resolved(cloudId, [toTarget(morpheus, server.id, cloudId)])
    }

    private static Target toTarget(MorpheusContext morpheus, Long serverId, Long expectedCloudId) {
        ComputeServer server = null
        try {
            server = morpheus.async.computeServer.get(serverId).blockingGet()
        } catch (Exception e) {
            log.warn("VmTargets: server ${serverId} could not be loaded: ${e.class.simpleName}")
        }
        if (server == null) {
            throw new IllegalStateException('The VM record was not found in Morpheus')
        }

        Long serverCloudId = server.cloud?.id
        if (serverCloudId == null || serverCloudId != expectedCloudId) {
            log.warn("VmTargets: server ${serverId} belongs to cloud ${serverCloudId}, expected ${expectedCloudId}")
            throw new IllegalStateException('A VM record does not belong to the expected cloud')
        }

        String vmMor = server.externalId?.trim()
        if (!SecurityUtil.isValidVmId(vmMor)) {
            log.warn("VmTargets: server ${serverId} has no usable vCenter VM reference")
            throw new IllegalStateException('The VM identifier is not available for VM "' + (server.name ?: serverId) + '"')
        }
        return new Target(serverId, server.name ?: ('vm ' + serverId), vmMor)
    }

    /** Distinct server ids of the instance, ordered by workload id so the first one is the primary VM. */
    private static List<Long> serverIdsOf(MorpheusContext morpheus, Instance instance) {
        List<Workload> workloads = []
        try {
            workloads = instance.containers ? new ArrayList<Workload>(instance.containers) : []
        } catch (Exception ignored) {
            workloads = []
        }
        if (workloads.isEmpty()) {
            try {
                workloads = morpheus.async.workload.list(new DataQuery().withFilter('instance.id', instance.id)).toList().blockingGet()
            } catch (Exception e) {
                log.warn("VmTargets: workloads of instance ${instance.id} could not be listed: ${e.class.simpleName}")
            }
        }

        List<Workload> ordered = workloads.sort(false) { Workload w -> w.id ?: Long.MAX_VALUE }
        Set<Long> serverIds = new LinkedHashSet<Long>()
        ordered.each { Workload w ->
            Long id = w.server?.id ?: w.serverId
            if (id != null) serverIds.add(id)
        }
        return new ArrayList<Long>(serverIds)
    }
}
