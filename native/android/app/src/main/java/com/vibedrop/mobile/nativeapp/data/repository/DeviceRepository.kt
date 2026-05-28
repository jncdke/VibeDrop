package com.vibedrop.mobile.nativeapp.data.repository

import com.vibedrop.mobile.nativeapp.core.model.ConnectionSnapshot
import com.vibedrop.mobile.nativeapp.core.model.ConnectionStatus
import com.vibedrop.mobile.nativeapp.core.model.DesktopDevice
import com.vibedrop.mobile.nativeapp.core.model.DiscoveredDesktop
import com.vibedrop.mobile.nativeapp.core.model.PairStatus
import com.vibedrop.mobile.nativeapp.data.local.DeviceDao
import com.vibedrop.mobile.nativeapp.data.local.DeviceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DeviceRepository(
    private val deviceDao: DeviceDao
) {
    fun observeDevices(): Flow<List<DesktopDevice>> {
        return deviceDao.observeDevices().map { devices ->
            devices.mapNotNull { it.toDesktopDevice() }
        }
    }

    suspend fun saveManualDesktop(
        displayName: String,
        host: String,
        port: Int,
        pin: String
    ): DesktopDevice {
        val now = System.currentTimeMillis()
        val cleanHost = host.trim()
        val cleanName = displayName.trim().ifBlank { cleanHost }
        val id = "manual:${cleanHost.lowercase()}:$port"
        val entity = DeviceEntity(
            id = id,
            stableId = id,
            displayName = cleanName,
            role = "desktop",
            host = cleanHost,
            ip = cleanHost.takeIf { it.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) },
            port = port,
            pin = pin.trim(),
            aliasesJson = "[]",
            capabilitiesJson = "[]",
            lastSeenAt = null,
            createdAt = now,
            updatedAt = now
        )
        deviceDao.upsert(entity)
        return entity.toDesktopDevice()!!
    }

    suspend fun updateDesktop(
        deviceId: String,
        displayName: String,
        host: String,
        port: Int,
        pin: String
    ): DesktopDevice {
        val existing = deviceDao.findById(deviceId)
        if (existing == null) {
            return saveManualDesktop(displayName, host, port, pin)
        }
        val cleanHost = host.trim()
        val cleanName = displayName.trim().ifBlank { cleanHost }
        val updated = existing.copy(
            displayName = cleanName,
            host = cleanHost,
            ip = cleanHost.takeIf { it.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) },
            port = port,
            pin = pin.trim(),
            updatedAt = System.currentTimeMillis()
        )
        deviceDao.upsert(updated)
        return updated.toDesktopDevice()!!
    }

    suspend fun deleteDesktop(deviceId: String) {
        deviceDao.deleteById(deviceId)
    }

    suspend fun savePairedDesktop(
        discovered: DiscoveredDesktop,
        status: PairStatus
    ): DesktopDevice {
        val serverId = status.serverId ?: discovered.serverId
        val hostname = status.hostname ?: discovered.hostname
        val ip = status.ip ?: discovered.ip
        val port = status.port ?: discovered.port
        val pin = status.pin.orEmpty()
        val id = "desktop:$serverId"
        val now = System.currentTimeMillis()
        val entity = DeviceEntity(
            id = id,
            stableId = serverId,
            displayName = hostname,
            role = "desktop",
            host = ip,
            ip = ip,
            port = port,
            pin = pin,
            aliasesJson = """["$hostname"]""",
            capabilitiesJson = "[]",
            lastSeenAt = now,
            createdAt = now,
            updatedAt = now
        )
        deviceDao.upsert(entity)
        return entity.toDesktopDevice()!!
    }

    suspend fun saveObservedDesktop(
        id: String,
        displayName: String,
        host: String?,
        ip: String?,
        port: Int?,
        pin: String?
    ) {
        val now = System.currentTimeMillis()
        deviceDao.upsert(
            DeviceEntity(
                id = id,
                stableId = id,
                displayName = displayName.ifBlank { host ?: ip ?: id },
                role = "desktop",
                host = host,
                ip = ip,
                port = port,
                pin = pin,
                aliasesJson = "[]",
                capabilitiesJson = "[]",
                lastSeenAt = null,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    private fun DeviceEntity.toDesktopDevice(): DesktopDevice? {
        val resolvedHost = host ?: ip ?: return null
        val resolvedPort = port ?: return null
        return DesktopDevice(
            id = id,
            stableId = stableId,
            displayName = displayName,
            host = resolvedHost,
            ip = ip,
            port = resolvedPort,
            pin = pin,
            connection = ConnectionSnapshot(ConnectionStatus.Disconnected)
        )
    }
}
