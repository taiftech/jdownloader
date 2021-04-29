package org.jdownloader.startup.commands;

import java.util.Iterator;
import java.util.Map.Entry;

public class ThreadDump extends AbstractStartupCommand {
    public ThreadDump() {
        super("threaddump");
    }

    @Override
    public void run(String command, String... parameters) {
        final Iterator<Entry<Thread, StackTraceElement[]>> it = Thread.getAllStackTraces().entrySet().iterator();
        while (it.hasNext()) {
            final Entry<Thread, StackTraceElement[]> next = it.next();
            final Thread thread = next.getKey();
            final StringBuilder sb = new StringBuilder();
            sb.append("Thread:" + next.getKey().getName() + "|" + next.getKey().getId() + "|Daemon:" + thread.isDaemon() + "|Alive:" + thread.isAlive() + "\r\n");
            for (final StackTraceElement stackTraceElement : next.getValue()) {
                sb.append("\tat " + stackTraceElement + "\r\n");
            }
            logger.severe(sb.toString());
        }
        logger.flush();
    }

    @Override
    public String getDescription() {
        return "prints thread dump";
    }
}
