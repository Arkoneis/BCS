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

-------
ТГ:Gatabarr
-------
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
