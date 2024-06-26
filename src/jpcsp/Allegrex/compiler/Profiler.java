/*
This file is part of jpcsp.

Jpcsp is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

Jpcsp is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with Jpcsp.  If not, see <http://www.gnu.org/licenses/>.
 */
package jpcsp.Allegrex.compiler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

import jpcsp.Allegrex.compiler.nativeCode.NativeCodeManager;
import jpcsp.Allegrex.compiler.nativeCode.NativeCodeSequence;
import jpcsp.settings.AbstractBoolSettingsListener;
import jpcsp.settings.Settings;

import org.apache.log4j.Logger;

/**
 * @author gid15
 *
 */
public class Profiler {

    public static Logger log = Logger.getLogger("profiler");
    private static boolean profilerEnabled = false;
    private static final HashMap<Integer, Long> callCounts = new HashMap<Integer, Long>();
    private static final HashMap<Integer, Long> instructionCounts = new HashMap<Integer, Long>();
    private static final HashMap<Integer, Long> backBranchCounts = new HashMap<Integer, Long>();
    private static final Long zero = Long.valueOf(0);
    private static final int detailedCodeBlockLogThreshold = 50;
    private static final int codeLogMaxLength = 700;
    private static final int backBranchMaxLength = 100;
    private static final int backBranchContextBefore = 5;
    private static final int backBranchContextAfter = 3;
    private static ProfilerEnabledSettingsListerner profilerEnabledSettingsListerner;
    private static int compilationCount;
    private static long compilationTimeMicros;
    private static long longestCompilationTimeMicros;

	private static class ProfilerEnabledSettingsListerner extends AbstractBoolSettingsListener {
		@Override
		protected void settingsValueChanged(boolean value) {
			setProfilerEnabled(value);
		}
	}

    public static void initialise() {
    	if (profilerEnabledSettingsListerner == null) {
    		profilerEnabledSettingsListerner = new ProfilerEnabledSettingsListerner();
    		Settings.getInstance().registerSettingsListener("Profiler", "emu.profiler", profilerEnabledSettingsListerner);
    	}

    	reset();
    }

    private static void setProfilerEnabled(boolean enabled) {
    	profilerEnabled = enabled;
    }

    public static boolean isProfilerEnabled() {
    	return profilerEnabled;
    }

    public static void reset() {
        if (!profilerEnabled) {
            return;
        }

        callCounts.clear();
        instructionCounts.clear();
        backBranchCounts.clear();
        compilationCount = 0;
        compilationTimeMicros = 0;
        longestCompilationTimeMicros = 0;
    }

    public static void exit() {
        if (!profilerEnabled) {
            return;
        }
        List<Integer> sortedBackBranches = new ArrayList<Integer>(backBranchCounts.keySet());
        Collections.sort(sortedBackBranches, new BackBranchComparator());

        List<CodeBlock> sortedCodeBlocks = new ArrayList<CodeBlock>(RuntimeContext.getCodeBlocks().values());
        Collections.sort(sortedCodeBlocks, new CodeBlockComparator());

        long allCycles = 0;
        for (Long instructionCount : instructionCounts.values()) {
            allCycles += instructionCount;
        }

        int count = 0;
        double avg = compilationCount == 0 ? 0.0 : compilationTimeMicros / (double) compilationCount / 1000;
        log.info(String.format("Compilation time %dms, %d calls, average %.1fms, longest %dms", compilationTimeMicros / 1000, compilationCount, avg, longestCompilationTimeMicros / 1000));
        log.info(String.format("CodeBlocks profiling information (%,d total cycles):", allCycles));
        for (CodeBlock codeBlock : sortedCodeBlocks) {
            long callCount = getCallCount(codeBlock);
            long instructionCount = getInstructionCount(codeBlock);

            if (callCount == 0 && instructionCount == 0) {
                // This and the following CodeBlocks have not been called since
                // the Profiler has been reset. Skip them.
                break;
            }
            logCodeBlock(codeBlock, allCycles, instructionCount, callCount, count, sortedBackBranches);
            count++;
        }
    }

    private static void logCodeBlock(CodeBlock codeBlock, long allCycles, long instructionCount, long callCount, int count, List<Integer> sortedBackBranches) {
        String name = codeBlock.getClassName();
        NativeCodeManager nativeCodeManager = Compiler.getInstance().getNativeCodeManager();
        NativeCodeSequence nativeCodeSequence = nativeCodeManager.getCompiledNativeCodeBlock(codeBlock.getStartAddress());
        if (nativeCodeSequence != null) {
            name = String.format("%s (%s)", name, nativeCodeSequence.getName());
        }
        int lowestAddress = codeBlock.getLowestAddress();
        int highestAddress = codeBlock.getHighestAddress();
        int length = (highestAddress - lowestAddress) / 4 + 1;
        double percentage = 0;
        if (allCycles != 0) {
            percentage = (instructionCount / (double) allCycles) * 100;
        }
        log.info(String.format("%s %,d instructions (%2.3f%%), %,d calls (%08X - %08X, length %d)", name, instructionCount, percentage, callCount, lowestAddress, highestAddress, length));
        if (count < detailedCodeBlockLogThreshold && codeBlock.getLength() <= codeLogMaxLength) {
            logCode(codeBlock);
        }
        for (int address : sortedBackBranches) {
            if (address < lowestAddress || address > highestAddress) {
                continue;
            }
            CodeInstruction codeInstruction = codeBlock.getCodeInstruction(address);
            if (codeInstruction == null) {
                continue;
            }
            int branchingToAddress = codeInstruction.getBranchingTo();
            // Add 2 for branch instruction itself and delay slot
            int backBranchLength = (address - branchingToAddress) / 4 + 2;
            log.info(String.format("  Back Branch %08X %,d times (length %d)", address, backBranchCounts.get(address), backBranchLength));
            if (count < detailedCodeBlockLogThreshold && backBranchLength <= backBranchMaxLength) {
                logCode(codeBlock, branchingToAddress, backBranchLength, backBranchContextBefore, backBranchContextAfter, address);
            }
        }
    }

    private static void logCode(CodeBlock codeBlock) {
        logCode(codeBlock, codeBlock.getLowestAddress(), codeBlock.getLength(), 0, 0, -1);
    }

    private static void logCode(SequenceCodeInstruction sequenceCodeInstruction, int highlightAddress) {
        for (CodeInstruction codeInstruction : sequenceCodeInstruction.getCodeSequence().getInstructions()) {
            logCode(codeInstruction, highlightAddress);
        }
    }

    private static void logCode(CodeInstruction codeInstruction, int highlightAddress) {
        if (codeInstruction == null) {
            return;
        }
        int address = codeInstruction.getAddress();
        if (codeInstruction instanceof SequenceCodeInstruction) {
            logCode((SequenceCodeInstruction) codeInstruction, highlightAddress);
        } else {
            int opcode = codeInstruction.getOpcode();
            String prefix = "   ";
            if (address == highlightAddress) {
                prefix = "-->";
            }
            log.info(String.format("%s %08X:[%08X]: %s", prefix, address, opcode, codeInstruction.disasm(address, opcode)));
        }
    }

    private static void logCode(CodeBlock codeBlock, int startAddress, int length, int contextBefore, int contextAfter, int highlightAddress) {
        for (int i = -contextBefore; i < length + contextAfter; i++) {
            int address = startAddress + (i * 4);
            CodeInstruction codeInstruction = codeBlock.getCodeInstruction(address);
            if (highlightAddress >= 0 && address == startAddress) {
                // Also highlight startAddress
                logCode(codeInstruction, startAddress);
            } else {
                logCode(codeInstruction, highlightAddress);
            }
        }
    }

    private static long getCallCount(CodeBlock codeBlock) {
        Long callCount = callCounts.get(codeBlock.getStartAddress());
        if (callCount == null) {
            return 0;
        }

        return callCount;
    }

    private static long getInstructionCount(CodeBlock codeBlock) {
        Long instructionCount = instructionCounts.get(codeBlock.getStartAddress());
        if (instructionCount == null) {
            return 0;
        }

        return instructionCount;
    }

    public static void addCall(int address) {
        Long callCount = callCounts.get(address);
        if (callCount == null) {
            callCount = zero;
        }

        callCounts.put(address, callCount + 1);
    }

    public static void addInstructionCount(int count, int address) {
        Long instructionCount = instructionCounts.get(address);
        if (instructionCount == null) {
            instructionCount = zero;
        }

        instructionCounts.put(address, instructionCount + count);
    }

    public static void addBackBranch(int address) {
        Long backBranchCount = backBranchCounts.get(address);
        if (backBranchCount == null) {
            backBranchCount = zero;
        }

        backBranchCounts.put(address, backBranchCount + 1);
    }

    private static class BackBranchComparator implements Comparator<Integer> {

        @Override
        public int compare(Integer address1, Integer address2) {
            long count1 = backBranchCounts.get(address1);
            long count2 = backBranchCounts.get(address2);

            if (count1 == count2) {
            	return 0;
            }
            return (count2 > count1 ? 1 : -1);
        }
    }

    private static class CodeBlockComparator implements Comparator<CodeBlock> {

        @Override
        public int compare(CodeBlock codeBlock1, CodeBlock codeBlock2) {
            long instructionCallCount1 = getInstructionCount(codeBlock1);
            long instructionCallCount2 = getInstructionCount(codeBlock2);

            if (instructionCallCount1 != instructionCallCount2) {
                return (instructionCallCount2 > instructionCallCount1 ? 1 : -1);
            }

            long callCount1 = getCallCount(codeBlock1);
            long callCount2 = getCallCount(codeBlock2);

            if (callCount1 != callCount2) {
                return (callCount2 > callCount1 ? 1 : -1);
            }

            return codeBlock2.getStartAddress() - codeBlock1.getStartAddress();
        }
    }

    public static void addCompilation(long compilationTimeMicros) {
    	compilationCount++;
    	Profiler.compilationTimeMicros += compilationTimeMicros;
    	if (compilationTimeMicros > longestCompilationTimeMicros) {
    		longestCompilationTimeMicros = compilationTimeMicros;
    	}
    }
}
