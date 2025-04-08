package ru.gatabarr.bcs

import org.bukkit.*
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
import kotlin.math.max
import kotlin.math.min

class Bcs : JavaPlugin(), Listener {
    private val structures = mutableListOf<Structure>()
    private val positionSelectors = mutableMapOf<UUID, Pair<Block?, Block?>>()
    private val goldShovelUsers = mutableSetOf<UUID>()
    private val debugPlayers = mutableSetOf<UUID>()
    private val checkingPlayers = mutableSetOf<UUID>()

    private lateinit var structuresFolder: File
    private lateinit var logsFolder: File
    private lateinit var configFile: File
    private lateinit var pluginConfig: YamlConfiguration

    override fun onEnable() {
        structuresFolder = File(dataFolder, "structures").apply { mkdirs() }
        logsFolder = File(dataFolder, "structure_logs").apply { mkdirs() }

        configFile = File(dataFolder, "config.yml")
        if (!configFile.exists()) saveResource("config.yml", false)
        pluginConfig = YamlConfiguration.loadConfiguration(configFile)

        server.pluginManager.registerEvents(this, this)
        loadStructures()

        logger.info("${ChatColor.GREEN}Плагин BCS v${description.version} включен!")
    }

    private fun loadStructures() {
        structures.clear()
        structuresFolder.listFiles()?.filter { it.extension.equals("yml", true) }?.forEach { file ->
            try {
                val config = YamlConfiguration.loadConfiguration(file)
                structures.add(Structure.fromConfig(config, file.nameWithoutExtension))
                logger.info("Загружена структура: ${file.nameWithoutExtension}")
            } catch (e: Exception) {
                logger.warning("Ошибка загрузки ${file.name}: ${e.message}")
            }
        }
    }

    override fun onCommand(sender: CommandSender, cmd: Command, label: String, args: Array<String>): Boolean {
        if (!cmd.name.equals("bcs", true)) return false

        when (args.getOrNull(0)?.lowercase()) {
            "reload" -> {
                if (!sender.hasPermission("bcs.reload")) return noPermission(sender)
                loadStructures()
                sender.sendMessage("${ChatColor.GREEN}Структуры перезагружены!")
                return true
            }
            "check" -> {
                if (sender !is Player) return playerOnly(sender)
                if (!sender.hasPermission("bcs.check")) return noPermission(sender)

                if (checkingPlayers.contains(sender.uniqueId)) {
                    checkingPlayers.remove(sender.uniqueId)
                    sender.sendMessage("${ChatColor.RED}Режим проверки отключен")
                } else {
                    checkingPlayers.add(sender.uniqueId)
                    sender.sendMessage("${ChatColor.GREEN}Режим проверки включен. Кликайте по блокам для проверки.")
                }
                return true
            }
            "rotate" -> {
                if (!sender.hasPermission("bcs.rotate")) return noPermission(sender)
                if (args.size < 2) {
                    sender.sendMessage("${ChatColor.YELLOW}Использование: /bcs rotate <имя_структуры>")
                    return true
                }
                rotateStructure(args[1], sender)
                return true
            }
            "debug" -> {
                if (sender !is Player) return playerOnly(sender)
                if (!sender.hasPermission("bcs.debug")) return noPermission(sender)

                if (debugPlayers.contains(sender.uniqueId)) {
                    debugPlayers.remove(sender.uniqueId)
                    sender.sendMessage("${ChatColor.RED}Режим отладки отключен")
                } else {
                    debugPlayers.add(sender.uniqueId)
                    sender.sendMessage("${ChatColor.GREEN}Режим отладки включен.")
                }
                return true
            }
            "structure" -> {
                if (args.size > 1 && args[1].equals("create", true)) {
                    if (sender !is Player) return playerOnly(sender)
                    if (!sender.hasPermission("bcs.create")) return noPermission(sender)
                    if (args.size < 3) {
                        sender.sendMessage("${ChatColor.YELLOW}Использование: /bcs structure create <name>")
                        return true
                    }
                    createStructure(sender as Player, args[2])
                    return true
                }
                return false
            }
            "info" -> {
                sender.sendMessage("${ChatColor.GOLD}==========BCS=========")
                sender.sendMessage("${ChatColor.AQUA}Версия: ${description.version}")
                sender.sendMessage("${ChatColor.AQUA}Автор: Arkoneis создано \"Gatabarr\"")
                sender.sendMessage("${ChatColor.GOLD}======================")
                return true
            }
            else -> {
                sender.sendMessage("${ChatColor.GOLD}=== BCS Help ===")
                sender.sendMessage("${ChatColor.GREEN}/bcs reload - Перезагрузить структуры")
                sender.sendMessage("${ChatColor.GREEN}/bcs check - Проверить структуры")
                sender.sendMessage("${ChatColor.GREEN}/bcs rotate <name> - Создать повернутые копии")
                sender.sendMessage("${ChatColor.GREEN}/bcs debug - Режим отладки")
                sender.sendMessage("${ChatColor.GREEN}/bcs structure create <name> - Создать структуру")
                sender.sendMessage("${ChatColor.GREEN}/bcs info - Информация о плагине")
                return true
            }
        }
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        val item = player.inventory.itemInMainHand

        if (item.type == Material.GOLDEN_SHOVEL && player.hasPermission("bcs.position")) {
            event.isCancelled = true
            val block = event.clickedBlock ?: return

            when (event.action) {
                Action.LEFT_CLICK_BLOCK -> {
                    positionSelectors[player.uniqueId] = block to positionSelectors[player.uniqueId]?.second
                    player.sendMessage("${ChatColor.GREEN}Первая точка установлена: ${block.x}, ${block.y}, ${block.z}")
                    goldShovelUsers.add(player.uniqueId)
                }
                Action.RIGHT_CLICK_BLOCK -> {
                    positionSelectors[player.uniqueId] = positionSelectors[player.uniqueId]?.first to block
                    player.sendMessage("${ChatColor.GREEN}Вторая точка установлена: ${block.x}, ${block.y}, ${block.z}")
                    goldShovelUsers.add(player.uniqueId)
                }
                else -> return
            }

            return
        }

        if (checkingPlayers.contains(player.uniqueId)) {
            event.isCancelled = true
            checkBlockLocation(player, event.clickedBlock ?: return)
            return
        }

        if (event.action != Action.RIGHT_CLICK_BLOCK) return

        val block = event.clickedBlock ?: return
        structures.filter { it.trigger == block.type }.forEach { structure ->
            if (checkStructure(block, structure, player)) {
                event.isCancelled = true
                executeCommands(player, structure.commands)

                if (debugPlayers.contains(player.uniqueId)) {
                    player.sendMessage("${ChatColor.GREEN}Структура '${structure.name}' активирована!")
                }
                return
            }
        }
    }

    private fun createStructure(player: Player, name: String) {
        val (first, second) = positionSelectors[player.uniqueId] ?: run {
            player.sendMessage("${ChatColor.RED}Сначала выделите область золотой лопатой!")
            return
        }

        if (first == null || second == null) {
            player.sendMessage("${ChatColor.RED}Необходимо установить обе точки!")
            return
        }

        val triggerBlock = player.getTargetBlockExact(10) ?: run {
            player.sendMessage("${ChatColor.RED}Смотрите на блок, который будет триггером!")
            return
        }

        val minX = min(first.x, second.x)
        val minY = min(first.y, second.y)
        val minZ = min(first.z, second.z)
        val maxX = max(first.x, second.x)
        val maxY = max(first.y, second.y)
        val maxZ = max(first.z, second.z)

        val blocks = mutableListOf<StructureBlock>()
        val world = first.world

        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    val block = world.getBlockAt(x, y, z)
                    if (block.type.isAir) continue

                    blocks.add(StructureBlock(
                        x - triggerBlock.x,
                        y - triggerBlock.y,
                        z - triggerBlock.z,
                        block.type
                    ))
                }
            }
        }

        val structureFile = File(structuresFolder, "$name.yml")
        val config = YamlConfiguration().apply {
            set("trigger", triggerBlock.type.name)
            set("commands", listOf("[message] &aСтруктура $name активирована!"))

            val blocksSection = createSection("blocks")
            blocks.forEachIndexed { index, block ->
                val blockSection = blocksSection.createSection("block$index")
                blockSection.set("dx", block.dx)
                blockSection.set("dy", block.dy)
                blockSection.set("dz", block.dz)
                blockSection.set("material", block.material.name)
            }
        }

        config.save(structureFile)
        loadStructures()

        player.sendMessage("${ChatColor.GREEN}Структура $name успешно создана!")
        logStructureCreation(player, name, blocks.size)
    }

    private fun checkBlockLocation(player: Player, block: Block) {
        val logFile = File(logsFolder, "${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(Date())}.yml")
        val logConfig = YamlConfiguration()

        structures.filter { it.trigger == block.type }.forEach { structure ->
            player.sendMessage("${ChatColor.GOLD}=== Проверка структуры '${structure.name}' ===")
            logConfig.set("structure", structure.name)
            logConfig.set("trigger_block", "${block.x},${block.y},${block.z}")

            val blocksSection = logConfig.createSection("blocks")
            var allCorrect = true

            structure.blocks.forEachIndexed { index, sBlock ->
                val targetBlock = block.world.getBlockAt(
                    block.x + sBlock.dx,
                    block.y + sBlock.dy,
                    block.z + sBlock.dz
                )
                val isCorrect = targetBlock.type == sBlock.material
                val status = if (isCorrect) "${ChatColor.GREEN}✔" else "${ChatColor.RED}✖"

                player.sendMessage("$status Блок на ${sBlock.dx},${sBlock.dy},${sBlock.dz}: " +
                        "ожидается ${sBlock.material}, найдено ${targetBlock.type}")

                blocksSection.set("block$index", mapOf(
                    "dx" to sBlock.dx,
                    "dy" to sBlock.dy,
                    "dz" to sBlock.dz,
                    "expected" to sBlock.material.name,
                    "actual" to targetBlock.type.name,
                    "correct" to isCorrect
                ))

                if (!isCorrect) allCorrect = false
            }

            if (allCorrect) {
                player.sendMessage("${ChatColor.GREEN}Структура '${structure.name}' собрана правильно!")
            } else {
                player.sendMessage("${ChatColor.RED}Структура '${structure.name}' собрана неправильно!")
            }
        }

        logConfig.save(logFile)
    }

    private fun rotateStructure(baseName: String, sender: CommandSender) {
        val baseFile = File(structuresFolder, "$baseName.yml")
        if (!baseFile.exists()) {
            sender.sendMessage("${ChatColor.RED}Файл $baseName.yml не найден!")
            return
        }

        try {
            val config = YamlConfiguration.loadConfiguration(baseFile)
            val original = Structure.fromConfig(config, baseName)

            structuresFolder.listFiles()?.filter {
                it.name.startsWith("${baseName}_") && it.name.endsWith(".yml")
            }?.forEach { it.delete() }

            createRotatedVariant(baseName, original, 90, sender)
            createRotatedVariant(baseName, original, 180, sender)
            createRotatedVariant(baseName, original, 270, sender)

            loadStructures()
            sender.sendMessage("${ChatColor.GREEN}Все повернутые структуры созданы!")
        } catch (e: Exception) {
            sender.sendMessage("${ChatColor.RED}Ошибка: ${e.message}")
            logger.warning("Ошибка ротации: ${e.message}")
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
        sender.sendMessage("${ChatColor.GREEN}Создан файл ${rotatedFile.name}")
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

    private fun checkStructure(center: Block, structure: Structure, player: Player? = null): Boolean {
        return structure.blocks.all { block ->
            val targetBlock = center.world.getBlockAt(
                center.x + block.dx,
                center.y + block.dy,
                center.z + block.dz
            )
            val isCorrect = targetBlock.type == block.material

            if (!isCorrect && player != null && debugPlayers.contains(player.uniqueId)) {
                player.sendMessage("${ChatColor.RED}Несоответствие: ${block.dx},${block.dy},${block.dz} " +
                        "ожидался ${block.material}, найден ${targetBlock.type}")
            }

            isCorrect
        }
    }

    private fun logStructureCreation(player: Player, name: String, blockCount: Int) {
        val logFile = File(logsFolder, "${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(Date())}_create.yml")
        val config = YamlConfiguration().apply {
            set("creator", player.name)
            set("structure_name", name)
            set("creation_time", System.currentTimeMillis())
            set("block_count", blockCount)
        }
        config.save(logFile)
    }

    private fun noPermission(sender: CommandSender): Boolean {
        sender.sendMessage("${ChatColor.RED}Нет прав!")
        return true
    }

    private fun playerOnly(sender: CommandSender): Boolean {
        sender.sendMessage("${ChatColor.RED}Команда только для игроков!")
        return true
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
