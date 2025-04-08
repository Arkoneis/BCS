package ru.gatabarr.bcs

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class Bcs : JavaPlugin() {
    private val structures = mutableListOf<Structure>()
    private lateinit var structuresFolder: File
    private lateinit var configFile: File
    private lateinit var pluginConfig: YamlConfiguration
    private val dateFormat = SimpleDateFormat("HH:mm:ss")
    private val debugPlayers = mutableSetOf<Player>()
    private val checkingPlayers = mutableSetOf<Player>()

    override fun onEnable() {
        configFile = File(dataFolder, "config.yml")
        if (!configFile.exists()) {
            saveResource("config.yml", false)
        }
        pluginConfig = YamlConfiguration.loadConfiguration(configFile)

        logger.info("§a[BCS] Плагин BCS v3.5 by Gatabarr запускается...")
        structuresFolder = File(dataFolder, "structures").apply {
            if (!exists()) mkdirs()
            logger.info("§a[BCS] Папка структур: ${absolutePath}")
        }
        loadStructures()
        server.pluginManager.registerEvents(StructureListener(), this)
        logger.info("§a[BCS] Плагин успешно включен!")
    }

    private fun loadStructures() {
        structures.clear()
        structuresFolder.listFiles()?.filter { it.extension.equals("yml", true) }?.forEach { file ->
            try {
                val config = YamlConfiguration.loadConfiguration(file)
                structures.add(Structure.fromConfig(config, file.nameWithoutExtension))
                logger.info("§a[BCS] Загружена структура: ${file.nameWithoutExtension}")
            } catch (e: Exception) {
                logger.warning("§c[BCS] Ошибка загрузки ${file.name}: ${e.message}")
            }
        }
    }

    override fun onCommand(sender: CommandSender, cmd: Command, label: String, args: Array<String>): Boolean {
        if (!cmd.name.equals("bcs", true)) return false

        when (args.firstOrNull()?.lowercase()) {
            "reload" -> {
                if (!sender.hasPermission("bcs.reload")) {
                    sender.sendMessage("§cНет прав!")
                    return true
                }
                loadStructures()
                sender.sendMessage("§aСтруктуры перезагружены!")
                return true
            }
            "check" -> {
                if (sender !is Player) return false
                if (!sender.hasPermission("bcs.check")) {
                    sender.sendMessage("§cНет прав!")
                    return true
                }
                if (checkingPlayers.contains(sender)) {
                    checkingPlayers.remove(sender)
                    sender.sendMessage("§cРежим проверки отключен")
                } else {
                    checkingPlayers.add(sender)
                    sender.sendMessage("§aРежим проверки включен. Кликайте по блокам для проверки.")
                }
                return true
            }
            "rotate" -> {
                if (!sender.hasPermission("bcs.rotate")) {
                    sender.sendMessage("§cНет прав!")
                    return true
                }
                if (args.size < 2) {
                    sender.sendMessage("§eИспользование: /bcs rotate <имя_структуры>")
                    return true
                }
                rotateStructure(args[1], sender)
                return true
            }
            "debug" -> {
                if (sender !is Player) return false
                if (!sender.hasPermission("bcs.debug")) {
                    sender.sendMessage("§cНет прав!")
                    return true
                }
                if (debugPlayers.contains(sender)) {
                    debugPlayers.remove(sender)
                    sender.sendMessage("§cРежим отладки отключен")
                } else {
                    debugPlayers.add(sender)
                    sender.sendMessage("§aРежим отладки включен. Будут показаны дополнительные сообщения.")
                }
                return true
            }
            else -> {
                sender.sendMessage("""
                    §e=== BCS Help ===
                    §a/bcs reload §7- Перезагрузить структуры
                    §a/bcs check §7- Проверить структуры
                    §a/bcs rotate <name> §7- Создать повернутые копии структуры
                    §a/bcs debug §7- Включить режим отладки
                """.trimIndent())
                return true
            }
        }
    }

    private fun rotateStructure(baseName: String, sender: CommandSender) {
        val baseFile = File(structuresFolder, "$baseName.yml")
        if (!baseFile.exists()) {
            sender.sendMessage("§cФайл $baseName.yml не найден!")
            return
        }

        try {
            val config = YamlConfiguration.loadConfiguration(baseFile)
            val original = Structure.fromConfig(config, baseName)

            // Удаляем старые повернутые версии
            structuresFolder.listFiles()?.filter {
                it.name.startsWith("${baseName}_") && it.name.endsWith(".yml")
            }?.forEach { it.delete() }

            // Создаем новые повернутые версии
            createRotatedVariant(baseName, original, 90, sender)
            createRotatedVariant(baseName, original, 180, sender)
            createRotatedVariant(baseName, original, 270, sender)

            // Перезагружаем структуры
            loadStructures()
            sender.sendMessage("§aВсе повернутые структуры созданы и загружены!")
        } catch (e: Exception) {
            sender.sendMessage("§cОшибка: ${e.message}")
            logger.warning("§c[BCS] Ошибка ротации: ${e.message}")
        }
    }

    private fun createRotatedVariant(baseName: String, original: Structure, degrees: Int, sender: CommandSender) {
        val rotatedBlocks = original.blocks.map { block ->
            when (degrees) {
                90 -> StructureBlock(-block.dz, block.dy, block.dx, block.material)
                180 -> StructureBlock(-block.dx, block.dy, -block.dz, block.material)
                270 -> StructureBlock(block.dz, block.dy, -block.dx, block.material)
                else -> block
            }
        }

        val rotatedStructure = original.copy(
            name = "${original.name}_$degrees",
            blocks = rotatedBlocks
        )

        val rotatedConfig = YamlConfiguration().apply {
            set("trigger", rotatedStructure.trigger.name)
            set("commands", rotatedStructure.commands)

            val blocksSection = createSection("blocks")
            rotatedStructure.blocks.forEachIndexed { index, block ->
                val blockSection = blocksSection.createSection("block$index")
                blockSection.set("dx", block.dx)
                blockSection.set("dy", block.dy)
                blockSection.set("dz", block.dz)
                blockSection.set("material", block.material.name)
            }
        }

        val rotatedFile = File(structuresFolder, "${rotatedStructure.name}.yml")
        rotatedConfig.save(rotatedFile)
        sender.sendMessage("§aСоздан файл ${rotatedFile.name}")
    }

    inner class StructureListener : Listener {
        @EventHandler
        fun onInteract(event: PlayerInteractEvent) {
            val player = event.player
            val block = event.clickedBlock ?: return

            if (checkingPlayers.contains(player)) {
                event.isCancelled = true
                checkBlockLocation(player, block)
                return
            }

            if (event.action != Action.RIGHT_CLICK_BLOCK) return

            // Проверяем все структуры с этим триггером
            structures.filter { it.trigger == block.type }.forEach { structure ->
                if (checkStructure(block, structure, player)) {
                    event.isCancelled = true
                    executeCommands(player, structure.commands)

                    if (debugPlayers.contains(player)) {
                        player.sendMessage("§aСтруктура '${structure.name}' активирована!")
                    }
                    return // Прерываем после первой успешной активации
                }
            }
        }

        private fun checkBlockLocation(player: Player, block: Block) {
            structures.filter { it.trigger == block.type }.forEach { structure ->
                player.sendMessage("§6=== Проверка структуры '${structure.name}' ===")
                var allCorrect = true

                structure.blocks.forEach { sBlock ->
                    val targetBlock = block.world.getBlockAt(
                        block.x + sBlock.dx,
                        block.y + sBlock.dy,
                        block.z + sBlock.dz
                    )
                    val isCorrect = targetBlock.type == sBlock.material
                    val status = if (isCorrect) "§a✔" else "§c✖"
                    player.sendMessage("$status Блок на ${sBlock.dx},${sBlock.dy},${sBlock.dz}: " +
                            "ожидается ${sBlock.material}, найдено ${targetBlock.type}")

                    if (!isCorrect) allCorrect = false
                }

                if (allCorrect) {
                    player.sendMessage("§aСтруктура '${structure.name}' собрана правильно!")
                } else {
                    player.sendMessage("§cСтруктура '${structure.name}' собрана неправильно!")
                }
            }
        }

        private fun executeCommands(player: Player, commands: List<String>) {
            val wasOp = player.isOp
            try {
                player.isOp = true
                commands.forEach { cmd ->
                    val processedCmd = cmd
                        .replace("%player%", player.name)
                        .replace("%world%", player.world.name)
                        .replace("%x%", player.location.blockX.toString())
                        .replace("%y%", player.location.blockY.toString())
                        .replace("%z%", player.location.blockZ.toString())

                    when {
                        cmd.startsWith("[console]") -> Bukkit.dispatchCommand(
                            Bukkit.getConsoleSender(),
                            processedCmd.substring(9)
                        )
                        cmd.startsWith("[message]") -> player.sendMessage(processedCmd.substring(9))
                        else -> player.performCommand(processedCmd)
                    }
                }
            } finally {
                player.isOp = wasOp
            }
        }
    }

    private fun checkStructure(center: Block, structure: Structure, player: Player? = null): Boolean {
        return structure.blocks.all { block ->
            val targetBlock = center.world.getBlockAt(
                center.x + block.dx,
                center.y + block.dy,
                center.z + block.dz
            )
            val isCorrect = targetBlock.type == block.material

            if (!isCorrect && player != null && debugPlayers.contains(player)) {
                player.sendMessage("§cНесоответствие: ${block.dx},${block.dy},${block.dz} " +
                        "ожидался ${block.material}, найден ${targetBlock.type}")
            }

            isCorrect
        }
    }

    data class Structure(
        val name: String,
        val trigger: Material,
        val blocks: List<StructureBlock>,
        val commands: List<String>
    ) {
        companion object {
            fun fromConfig(config: YamlConfiguration, name: String): Structure {
                val triggerStr = config.getString("trigger")
                    ?: throw IllegalArgumentException("Не указан триггерный блок")
                val trigger = Material.matchMaterial(triggerStr)
                    ?: throw IllegalArgumentException("Триггерный блок '$triggerStr' не найден")

                val blocksSection = config.getConfigurationSection("blocks")
                    ?: throw IllegalArgumentException("Отсутствует секция blocks")

                val blocks = mutableListOf<StructureBlock>()
                for (key in blocksSection.getKeys(false)) {
                    val blockSection = blocksSection.getConfigurationSection(key)
                        ?: continue

                    val materialStr = blockSection.getString("material")
                        ?: throw IllegalArgumentException("Не указан материал для блока $key")
                    val material = Material.matchMaterial(materialStr)
                        ?: throw IllegalArgumentException("Материал '$materialStr' для блока $key не найден")

                    blocks.add(StructureBlock(
                        blockSection.getInt("dx"),
                        blockSection.getInt("dy"),
                        blockSection.getInt("dz"),
                        material
                    ))
                }

                return Structure(
                    name,
                    trigger,
                    blocks,
                    config.getStringList("commands")
                )
            }
        }
    }

    data class StructureBlock(
        val dx: Int,
        val dy: Int,
        val dz: Int,
        val material: Material
    )
}