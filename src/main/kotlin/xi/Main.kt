package xi

import com.beust.klaxon.*
import javafx.application.Platform
import javafx.geometry.Orientation
import javafx.scene.control.TextArea
import javafx.scene.input.KeyCode
import javafx.scene.layout.Priority
import ru.yole.jkid.serialization.serialize
import tornadofx.*
import java.io.File
import java.io.PrintWriter
import java.nio.file.Paths
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread


fun pathToXiCore(): String = Paths.get("../xi-editor/rust/target/debug/xi-core").toAbsolutePath().normalize().toString()

class XiFront : App(CodeView::class)

class CodeView : View() {
    private var code: TextArea by singleAssign()
    private var ast: TextArea by singleAssign()
    private var xi: Xi by singleAssign()

    private fun startXi(): Xi = Xi(Tab.fromTextArea(code), Tab.fromTextArea(ast))

    override val root = vbox {
        title = "Xi"
        minWidth = 640.0
        minHeight = 480.0

        splitpane {
            orientation = Orientation.HORIZONTAL
            setDividerPositions(0.5)
            vboxConstraints { vGrow = Priority.ALWAYS }

            code = textarea {
                setOnKeyPressed { event ->
                    event.consume()
                    val code = event.code
                    val direction = when (code) {
                        KeyCode.W -> if (event.isControlDown) "extend_selection" else return@setOnKeyPressed
                        KeyCode.UP -> "move_up"
                        KeyCode.LEFT -> "move_left"
                        KeyCode.DOWN -> "move_down"
                        KeyCode.RIGHT -> "move_right"
                        KeyCode.HOME -> "move_to_left_end_of_line"
                        KeyCode.END -> "move_to_right_end_of_line"
                        KeyCode.BACK_SPACE -> "delete_backward"
                        KeyCode.ENTER -> "insert_newline"
                        else -> return@setOnKeyPressed
                    }
                    xi.simple_command(direction)
                }

                setOnKeyTyped { event ->
                    event.consume()
                    if (event.isControlDown) {
                        return@setOnKeyTyped
                    }
                    val c = when (event.character) {
                        "\r" -> "\n"
                        "\b" -> return@setOnKeyTyped
                        else -> event.character
                    }
                    xi.type(c)
                }
            }

            ast = textarea("AST") { }
        }

        xi = startXi()
    }
}


fun runOnEdt(f: () -> Unit) {
    Platform.runLater(f)
}


sealed class Event {
    class Error(val msg: String) : Event()
    class FromXi(val msg: String) : Event()
    class ToXi(val method: String, val params: Any) : Event()
}


class Xi(private val codeTab: Tab, private val astTab: Tab) {
    private val xiProcess = ProcessBuilder(pathToXiCore()).start()

    private val enqueue: (Event) -> Unit = run {
        val queue = ArrayBlockingQueue<Event>(64)
        thread {
            val pw = PrintWriter(xiProcess.outputStream)

            val loop = Loop(
                    codeTab.updater,
                    astTab.updater,
                    { message ->
                        pw.println(message)
                        pw.flush()
                    }
            )

            while (true) {
                loop.dispatch(queue.take())
            }
        }

        fun(e: Event): Unit {
            queue.put(e)
        }
    }

    init {
        thread {
            xiProcess.errorStream.bufferedReader().forEachLine {
                enqueue(Event.Error(it))
            }
        }

        thread {
            xiProcess.inputStream.bufferedReader().forEachLine { line ->
                enqueue(Event.FromXi(line))
            }
        }

        command("new_tab")
        command("edit", OpenTabCommand("example.json"))
    }

    private fun command(method: String, params: Any = emptyList<Nothing>()) {
        enqueue(Event.ToXi(method, params))
    }

    fun type(character: String) {
        command("edit", InsertCommand(character))
    }

    fun simple_command(cmd: String) {
        command("edit", object : TabCommand(cmd) {
            override val params = emptyList<Nothing>()
        })
    }

}


interface Tab {
    fun setText(text: String)
    fun setCaretPosition(caretPosition: Int)
    fun setSelection(start: Int, length: Int)

    val updater: ((Tab.() -> Unit) -> Unit) get() = { runOnEdt { this.it() } }

    companion object {
        fun fromTextArea(ta: TextArea): Tab = object : Tab {
            override fun setText(text: String) {
                ta.text = text
            }

            override fun setCaretPosition(caretPosition: Int) {
                ta.positionCaret(caretPosition)
            }

            override fun setSelection(start: Int, length: Int) {
                ta.selectRange(start, start + length)
            }
        }
    }
}


class Loop(
        private val updateCode: (Tab.() -> Unit) -> Unit,
        private val updateAst: (Tab.() -> Unit) -> Unit,
        private val sendToXi: (String) -> Unit
) {
    val logRequests = false
    var id = 0

    fun dispatch(event: Event) {
        when (event) {
            is Event.Error -> println("ERROR: ${event.msg}")

            is Event.FromXi -> {
                if (logRequests) println("<- ${event.msg}")
                val json = Parser().parse(event.msg.byteInputStream()) as JsonObject
                when (json.string("method")) {
                    "update" -> {
                        val params = json.obj("params")!!
                        check(params.string("tab") == "0")
                        onUpdate(params
                                .obj("update")!!
                                .array<JsonObject>("ops")!!)
                    }

                    "ast" -> {
                        updateAst {
                            setText(json.obj("params")!!.string("ast")!!)
                        }
                    }
                }
            }

            is Event.ToXi -> {
                val cmd = Command(id, event.method, event.params)
                id += 1
                val msg = serialize(cmd)
                if (logRequests) println("-> $msg")
                sendToXi(msg)
            }
        }
    }

    private fun onUpdate(ops: JsonArray<JsonObject>) {
        val lines = ops
                .mapNotNull { it.array<JsonObject>("lines") }
                .flatMap { it }

        val text = lines
                .map { it.string("text") }
                .joinToString(separator = "")

        var currentPosition = 0
        var selection: Pair<Int, Int>? = null
        var cursorPosition = 0
        for (line in lines) {
            val cursor = line.array<Int>("cursor")
            if (cursor != null) {
                cursorPosition = currentPosition + cursor[0]
            }
            val style = line.array<Int>("styles")
            if (style != null && style.size == 3) {
                if (selection == null) {
                    selection = currentPosition + style[0] to style[1]
                } else {
                    selection = selection.first to selection.second + style[1]
                }
            }
            currentPosition += line.string("text")!!.length
        }
        val sel = selection

        updateCode {
            setText(text)
            setCaretPosition(cursorPosition)
            if (sel != null) {
                setSelection(sel.first, sel.second)
            }
        }
    }
}

data class Command(val id: Int, val method: String, val params: Any)

@Suppress("unused")
abstract class TabCommand(val method: String) {
    val tab = "0"
    abstract val params: Any
}

@Suppress("unused")
class OpenTabCommand(filename: String) : TabCommand("open") {
    override val params = object {
        val filename = filename
    }
}

@Suppress("unused")
class InsertCommand(chars: String) : TabCommand("insert") {
    override val params = object {
        val chars = chars
    }
}
