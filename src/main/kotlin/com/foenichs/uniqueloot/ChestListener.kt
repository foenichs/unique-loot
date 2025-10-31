@file:Suppress("DEPRECATION")

package com.foenichs.uniqueloot

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.*
import org.bukkit.block.Barrel
import org.bukkit.block.Block
import org.bukkit.block.Chest
import org.bukkit.block.DoubleChest
import org.bukkit.block.data.type.Chest.Type
import org.bukkit.entity.Piglin
import org.bukkit.entity.Player
import org.bukkit.entity.memory.MemoryKey
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.loot.LootContext
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.*
import java.util.concurrent.CompletableFuture

class ChestListener(private val plugin: UniqueLoot) : Listener {

    private val playerInventories: MutableMap<UUID, MutableMap<String, Opened>> = mutableMapOf()
    private val viewers: MutableMap<String, MutableSet<UUID>> = mutableMapOf()

    // sentinel to mark opened but empty inventories in the database
    companion object {
        private const val EMPTY_SENTINEL_SLOT = -1
        private const val EMPTY_SENTINEL_DATA = "-"
    }

    private data class Opened(val inv: Inventory, val anchorBlock: Block, val type: ContainerKind)
    private enum class ContainerKind { SINGLE_CHEST, DOUBLE_CHEST, BARREL }

    // Serialization
    @Throws(IOException::class)
    private fun ItemStack.serializeToString(): String {
        val baos = ByteArrayOutputStream()
        BukkitObjectOutputStream(baos).use { out -> out.writeObject(this) }
        return Base64.getEncoder().encodeToString(baos.toByteArray())
    }

    @Throws(IOException::class, ClassNotFoundException::class)
    private fun deserializeItemStack(data: String): ItemStack? {
        return try {
            val bytes = Base64.getDecoder().decode(data)
            ByteArrayInputStream(bytes).use { bais ->
                BukkitObjectInputStream(bais).use { inStream -> inStream.readObject() as? ItemStack }
            }
        } catch (ex: Exception) {
            plugin.logger.warning("Failed to deserialize ItemStack: ${ex.message}")
            null
        }
    }

    // Canonical IDs

    private fun canonicalId(block: Block): String {
        return when (val state = block.state) {
            is Chest -> canonicalChestId(state)
            is Barrel -> "${block.world.uid}:${block.x},${block.y},${block.z}"
            else -> "${block.world.uid}:${block.x},${block.y},${block.z}"
        }
    }

    private fun canonicalChestId(chest: Chest): String {
        val holder = chest.inventory.holder
        if (holder is DoubleChest) {
            val left = holder.leftSide as? Chest
            val right = holder.rightSide as? Chest
            if (left != null && right != null) {
                val minX = minOf(left.x, right.x)
                val minY = minOf(left.y, right.y)
                val minZ = minOf(left.z, right.z)
                return "${left.world.uid}:${minX},${minY},${minZ}"
            }
        }
        return "${chest.world.uid}:${chest.x},${chest.y},${chest.z}"
    }

    private fun isBlockedAbove(block: Block): Boolean {
        val above = block.world.getBlockAt(block.x, block.y + 1, block.z)
        return above.type.isOccluding
    }

    // Events

    @EventHandler
    fun onInteract(e: PlayerInteractEvent) {
        if (e.action != Action.RIGHT_CLICK_BLOCK) return
        val block = e.clickedBlock ?: return
        val state = block.state
        val player = e.player

        val isChest = state is Chest
        val isBarrel = state is Barrel
        if (!isChest && !isBarrel) return

        val lootTable = when (state) {
            is Chest -> state.lootTable
            is Barrel -> state.lootTable
            else -> null
        } ?: return

        // Vanilla restrictions
        if (isBlockedAbove(block)) return
        if (player.isSneaking && !player.inventory.itemInMainHand.type.isAir) {
            return
        }

        e.isCancelled = true

        // Anger piglins
        val radius = 16.0
        val world = block.world
        world.getNearbyEntities(block.location, radius, radius, radius).filterIsInstance<Piglin>().forEach { piglin ->
            piglin.setMemory(MemoryKey.ANGRY_AT, player.uniqueId)
        }

        // Trigger sculk vibrations
        block.world.sendGameEvent(player, GameEvent.CONTAINER_OPEN, block.location.toVector())

        val lootTableKey = lootTable.key // this is the NamespacedKey
        grantWarPigsAdvancement(player, lootTableKey)

        val containerId = if (isChest) canonicalChestId(state) else canonicalId(block)
        val kind = when {
            isChest && state.blockData is org.bukkit.block.data.type.Chest && (state.blockData as org.bukkit.block.data.type.Chest).type != Type.SINGLE -> ContainerKind.DOUBLE_CHEST
            isChest -> ContainerKind.SINGLE_CHEST
            else -> ContainerKind.BARREL
        }

        val perPlayer = playerInventories.getOrPut(player.uniqueId) { mutableMapOf() }
        perPlayer[containerId]?.let { opened ->
            openFor(player, containerId, opened)
            return
        }

        CompletableFuture.supplyAsync { loadFromDb(player.uniqueId, containerId) }.whenComplete { saved, err ->
            if (err != null) plugin.logger.warning(
                "DB load failed for ${player.uniqueId}/$containerId: ${err.message}"
            )

            Bukkit.getScheduler().runTask(plugin, Runnable {
                val size = when (kind) {
                    ContainerKind.BARREL, ContainerKind.SINGLE_CHEST -> 27
                    ContainerKind.DOUBLE_CHEST -> 54
                }
                val title = when (kind) {
                    ContainerKind.BARREL -> Component.text("Loot ").append(Component.translatable("container.barrel"))
                    ContainerKind.SINGLE_CHEST, ContainerKind.DOUBLE_CHEST -> Component.text("Loot ")
                        .append(Component.translatable("container.chest"))
                }

                val inv = Bukkit.createInventory(player, size, title)

                if (saved != null) {
                    for ((slot, data) in saved) {
                        try {
                            deserializeItemStack(data)?.let { inv.setItem(slot, it) }
                        } catch (_: Exception) {
                        }
                    }
                } else {
                    val rng = Random()

                    try {
                        val context = LootContext.Builder(block.location).lootedEntity(player).build()
                        if (isChest) {
                            val holder = state.inventory.holder
                            if (holder is DoubleChest) {
                                (holder.leftSide as? Chest)?.lootTable?.fillInventory(inv, rng, context)
                                (holder.rightSide as? Chest)?.lootTable?.fillInventory(inv, rng, context)
                            } else {
                                lootTable.fillInventory(inv, rng, context)
                            }
                        } else {
                            lootTable.fillInventory(inv, rng, context)
                        }
                    }
                    catch (ex: IllegalArgumentException)
                    {
                        player.sendActionBar(Component.text("Failed to generate loot for this container").color(
                            NamedTextColor.RED))

                        player.playSound(block.location, Sound.BLOCK_CHEST_LOCKED, 1.0f, 1.0f)
                        Particle.ANGRY_VILLAGER.builder().location(block.location.add(0.5,0.5,0.5)).count(14).receivers(32, true).spawn()

                        plugin.logger.warning("Failed to fill loot: ${ex.message}")

                        return@Runnable
                    }
                }

                if (inv.storageContents.size == 0) return@Runnable

                val opened = Opened(inv, block, kind)
                perPlayer[containerId] = opened
                openFor(player, containerId, opened)
            })
        }
    }

    @EventHandler
    fun onClose(e: InventoryCloseEvent) {
        val player = e.player as? Player ?: return
        val perPlayer = playerInventories[player.uniqueId] ?: return
        val entry = perPlayer.entries.find { it.value.inv === e.inventory } ?: return

        val containerId = entry.key
        val opened = entry.value

        // collect items to store or leave empty to mark as opened but empty
        val toStore = buildList {
            for (slot in 0 until opened.inv.size) {
                opened.inv.getItem(slot)?.let { add(slot to it.serializeToString()) }
            }
        }

        CompletableFuture.runAsync { saveToDb(player.uniqueId, containerId, toStore) }

        closeFor(player, containerId, opened)

        // Use the real block associated with this virtual inventory
        val block = opened.anchorBlock
        block.world.sendGameEvent(player, GameEvent.CONTAINER_CLOSE, block.location.toVector())
    }

    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        val uuid = e.player.uniqueId
        playerInventories.remove(uuid)
        viewers.values.forEach { it.remove(uuid) }
        viewers.entries.removeIf { it.value.isEmpty() }
    }

    // Open / Close

    private fun openFor(player: Player, containerId: String, opened: Opened) {
        if (player.gameMode == GameMode.SPECTATOR) return
        val set = viewers.getOrPut(containerId) { mutableSetOf() }
        val wasEmpty = set.isEmpty()
        set.add(player.uniqueId)
        if (wasEmpty) playOpenAnimation(opened)
        player.openInventory(opened.inv)
    }

    private fun closeFor(player: Player, containerId: String, opened: Opened) {
        if (player.gameMode == GameMode.SPECTATOR) return
        val set = viewers[containerId] ?: return
        set.remove(player.uniqueId)
        if (set.isEmpty()) {
            viewers.remove(containerId)
            playCloseAnimation(opened)
        }
    }

    private fun playOpenAnimation(opened: Opened) {
        val loc = getChestAnimationLocation(opened)
        when (opened.type) {
            ContainerKind.BARREL -> {
                opened.anchorBlock.world.playSound(loc, Sound.BLOCK_BARREL_OPEN, 1.0f, 1.0f)
                val state = opened.anchorBlock.state
                if (state is Barrel) state.open()
            }

            else -> {
                opened.anchorBlock.world.playSound(loc, Sound.BLOCK_CHEST_OPEN, 1.0f, 1.0f)
                val state = opened.anchorBlock.state
                if (state is Chest) state.open()
            }
        }
    }

    private fun playCloseAnimation(opened: Opened) {
        val loc = getChestAnimationLocation(opened)
        when (opened.type) {
            ContainerKind.BARREL -> {
                opened.anchorBlock.world.playSound(loc, Sound.BLOCK_BARREL_CLOSE, 1.0f, 1.0f)
                val state = opened.anchorBlock.state
                if (state is Barrel) state.close()
            }

            else -> {
                opened.anchorBlock.world.playSound(loc, Sound.BLOCK_CHEST_CLOSE, 1.0f, 1.0f)
                val state = opened.anchorBlock.state
                if (state is Chest) state.close()
            }
        }
    }

    private fun getChestAnimationLocation(opened: Opened): Location {
        val block = opened.anchorBlock
        if (opened.type != ContainerKind.DOUBLE_CHEST) return block.location.add(0.5, 0.5, 0.5)
        val state = block.state as? Chest ?: return block.location.add(0.5, 0.5, 0.5)
        val holder = state.inventory.holder as? DoubleChest ?: return block.location.add(0.5, 0.5, 0.5)
        val left = holder.leftSide as? Chest
        val right = holder.rightSide as? Chest
        if (left != null && right != null) {
            val midX = (left.x + right.x) / 2.0 + 0.5
            val midY = (left.y + right.y) / 2.0 + 0.5
            val midZ = (left.z + right.z) / 2.0 + 0.5
            return Location(left.world, midX, midY, midZ)
        }
        return block.location.add(0.5, 0.5, 0.5)
    }

    fun grantWarPigsAdvancement(player: Player, lootTable: NamespacedKey) {
        val bastionLootTables = setOf(
            NamespacedKey.minecraft("chests/bastion_hoglin_stable"),
            NamespacedKey.minecraft("chests/bastion_other"),
            NamespacedKey.minecraft("chests/bastion_treasure"),
            NamespacedKey.minecraft("chests/bastion_bridge")
        )

        if (lootTable !in bastionLootTables) return

        val advancementKey = NamespacedKey.minecraft("nether/loot_bastion")
        val advancement = player.server.getAdvancement(advancementKey) ?: return
        val progress = player.getAdvancementProgress(advancement)
        if (progress.isDone) return

        for (criterion in progress.remainingCriteria) {
            progress.awardCriteria(criterion)
        }
    }

    // DB Persistence

    private fun loadFromDb(playerId: UUID, containerId: String): List<Pair<Int, String>>? {
        try {
            plugin.connection.prepareStatement(
                "SELECT slot, item_data FROM player_chest WHERE player_uuid = ? AND chest_id = ? ORDER BY slot ASC"
            ).use { stmt ->
                stmt.setString(1, playerId.toString())
                stmt.setString(2, containerId)
                stmt.executeQuery().use { rs ->
                    val out = mutableListOf<Pair<Int, String>>()
                    var sawEmptySentinel = false
                    while (rs.next()) {
                        val slot = rs.getInt("slot")
                        if (slot == EMPTY_SENTINEL_SLOT) {
                            sawEmptySentinel = true
                            continue
                        }
                        val data = rs.getString("item_data") ?: continue
                        out.add(slot to data)
                    }
                    return when {
                        out.isNotEmpty() -> out
                        sawEmptySentinel -> emptyList()
                        else -> null
                    }
                }
            }
        } catch (t: Throwable) {
            plugin.logger.warning("loadFromDb error: ${t.message}")
            return null
        }
    }

    private fun saveToDb(playerId: UUID, containerId: String, items: List<Pair<Int, String>>) {
        try {
            plugin.connection.autoCommit = false
            plugin.connection.prepareStatement(
                "DELETE FROM player_chest WHERE player_uuid = ? AND chest_id = ?"
            ).use { del ->
                del.setString(1, playerId.toString())
                del.setString(2, containerId)
                del.executeUpdate()
            }
            if (items.isNotEmpty()) {
                plugin.connection.prepareStatement(
                    "INSERT INTO player_chest (player_uuid, chest_id, slot, item_data) VALUES (?, ?, ?, ?)"
                ).use { ins ->
                    for ((slot, data) in items) {
                        ins.setString(1, playerId.toString())
                        ins.setString(2, containerId)
                        ins.setInt(3, slot)
                        ins.setString(4, data)
                        ins.addBatch()
                    }
                    ins.executeBatch()
                }
            } else {
                // write a sentinel row to mark opened but empty
                plugin.connection.prepareStatement(
                    "INSERT INTO player_chest (player_uuid, chest_id, slot, item_data) VALUES (?, ?, ?, ?)"
                ).use { ins ->
                    ins.setString(1, playerId.toString())
                    ins.setString(2, containerId)
                    ins.setInt(3, EMPTY_SENTINEL_SLOT)
                    ins.setString(4, EMPTY_SENTINEL_DATA)
                    ins.executeUpdate()
                }
            }
            plugin.connection.commit()
        } catch (t: Throwable) {
            plugin.logger.warning("saveToDb error: ${t.message}")
            try {
                plugin.connection.rollback()
            } catch (_: Throwable) {
            }
        } finally {
            try {
                plugin.connection.autoCommit = true
            } catch (_: Throwable) {
            }
        }
    }
}