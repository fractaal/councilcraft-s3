package startupfix.boot;

import com.sun.tools.attach.VirtualMachine;

/** The helper process Bootstrap starts: attaches to the game's JVM and loads the agent jar into it. */
public final class Attach {
    private Attach() {}

    public static void main(String[] args) throws Exception {
        VirtualMachine vm = VirtualMachine.attach(args[0]);
        try {
            vm.loadAgent(args[1], args.length > 2 ? args[2] : "");
        } finally {
            vm.detach();
        }
    }
}
