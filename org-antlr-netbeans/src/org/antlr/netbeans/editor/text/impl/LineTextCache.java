/*
 * This file is not licensed for distribution in source or binary form.
 */
package org.antlr.netbeans.editor.text.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.antlr.netbeans.editor.text.NormalizedTextChangeCollection;
import org.antlr.netbeans.editor.text.TextChange;
import org.openide.util.Parameters;

/**
 *
 * @author sam
 */
public class LineTextCache {

    private static int MaximumBlockLength = 64;

    private final int length;
    private final int lineCount;

    private final List<List<String>> lineData = new ArrayList<List<String>>();
    private final List<Integer> blockOffsets = new ArrayList<Integer>();
    private final List<Integer> blockLineOffsets = new ArrayList<Integer>();
    private final List<List<Integer>> lineOffsets = new ArrayList<List<Integer>>();

    public LineTextCache(String data) {
        Parameters.notNull("data", data);

        length = data.length();

        List<String> currentBlock = new ArrayList<String>();
        List<Integer> currentOffsets = new ArrayList<Integer>();
        @SuppressWarnings("LocalVariableHidesMemberVariable")
        int lineCount = 0;
        int blockStart = 0;
        int blockLineStart = 0;
        int lineStart = 0;
        for (int i = 0; i <= data.length(); i++) {
            if (i == data.length() || data.charAt(i) == '\n') {
                int lineEnd = Math.min(i + 1, data.length());
                currentBlock.add(data.substring(lineStart, lineEnd));
                currentOffsets.add(lineStart - blockStart);
                lineCount++;
                lineStart = i + 1;
                if (i == data.length() || currentBlock.size() == MaximumBlockLength) {
                    lineData.add(currentBlock);
                    lineOffsets.add(currentOffsets);
                    blockOffsets.add(blockStart);
                    blockLineOffsets.add(blockLineStart);
                    currentBlock = new ArrayList<String>();
                    currentOffsets = new ArrayList<Integer>();
                    blockStart = lineStart;
                    blockLineStart = lineCount;
                }
            }
        }

        this.lineCount = lineCount;
    }

    private LineTextCache(int length, int lineCount) {
        this.length = length;
        this.lineCount = lineCount;
    }

    public int getLength() {
        return length;
    }

    public int getLineCount() {
        return lineCount;
    }

    public List<List<String>> getLineData() {
        return lineData;
    }

    public List<Integer> getBlockOffsets() {
        return blockOffsets;
    }

    public List<Integer> getBlockLineOffsets() {
        return blockLineOffsets;
    }

    public List<List<Integer>> getLineOffsets() {
        return lineOffsets;
    }

    public int getBlockFromLineNumber(int lineNumber) {
        int block = Collections.binarySearch(blockLineOffsets, lineNumber);
        if (block < 0) {
            block = -(block + 1) - 1;
        }

        return block;
    }

    public int getBlockFromPosition(int position) {
        int block = Collections.binarySearch(blockOffsets, position);
        if (block < 0) {
            block = -(block + 1) - 1;
        }

        return block;
    }

    public int getBlockLineFromPosition(int block, int position) {
        int blockLine = Collections.binarySearch(lineOffsets.get(block), position - blockOffsets.get(block));
        if (blockLine < 0) {
            blockLine = -(blockLine + 1) - 1;
        }

        return blockLine;
    }

    public int getLineNumberFromPosition(int position) {
        int block = getBlockFromPosition(position);
        int blockLine = getBlockLineFromPosition(block, position);
        return blockLine + blockLineOffsets.get(block);
    }

    public LineTextCache applyChanges(NormalizedTextChangeCollection changes) {
        int delta = 0;
        int lineDelta = 0;
        for (TextChange change : changes) {
            delta += change.getDelta();
            lineDelta += change.getLineCountDelta();
        }

        LineTextCache next = new LineTextCache(this.length + delta, this.lineCount + lineDelta);

        int oldBlock = 0;
        int oldLine = 0;
        int oldColumn = 0;
        int oldBlockLine = 0; // index of oldLine within oldBlock
        int newPosition = 0;
        List<String> modifiedBlock = null;
        for (int i = 0; i <= changes.size(); i++) {
            TextChange currentChange = i < changes.size() ? changes.get(i) : null;

            /*
             * move forward to this change
             */

            // 1. finish off the current line if necessary
            boolean previousLineEnded = oldColumn == 0;
            if (previousLineEnded && modifiedBlock != null && !modifiedBlock.isEmpty()) {
                String lastLine = modifiedBlock.get(modifiedBlock.size() - 1);
                previousLineEnded = lastLine.charAt(lastLine.length() - 1) == '\n';
            }

            if (!previousLineEnded && (currentChange == null || getLineEnd(oldBlock, oldLine) <= currentChange.getOldPosition())) {
                if (modifiedBlock == null) {
                    modifiedBlock = new ArrayList<String>();
                }

                int index = modifiedBlock.size() - 1;
                String text = index >= 0 ? modifiedBlock.get(index) : null;
                String oldLineText = getLineText(oldBlock, oldLine);
                String appendText = oldLineText.substring(oldColumn);
                if (text == null || text.charAt(text.length() - 1) == '\n') {
                    modifiedBlock.add(appendText);
                } else {
                    modifiedBlock.set(index, text + appendText);
                }

                newPosition += appendText.length();
                oldLine++;
                oldColumn = 0;
                oldBlockLine++;
                if (oldBlockLine == lineData.get(oldBlock).size()) {
                    oldBlock++;
                    oldBlockLine = 0;
                }
            }

            // 2. finish off the current block if necessary
            if (modifiedBlock != null && (currentChange == null || getBlockEnd(oldBlock) <= currentChange.getOldPosition())) {
                assert oldColumn == 0 : "Should be at the beginning of a line.";

                List<String> remainingLines = lineData.get(oldBlock).subList(oldBlockLine, lineData.get(oldBlock).size());
                if (modifiedBlock.size() + lineData.get(oldBlock).size() - oldBlockLine < MaximumBlockLength) {
                    modifiedBlock.addAll(remainingLines);
                    for (String text : remainingLines) {
                        newPosition += text.length();
                    }

                    if (!modifiedBlock.isEmpty()) {
                        next.lineData.add(modifiedBlock);
                    }
                } else {
                    if (!modifiedBlock.isEmpty()) {
                        next.lineData.add(modifiedBlock);
                    }
                    if (oldBlockLine > 0) {
                        next.lineData.add(new ArrayList<String>(remainingLines));
                    } else {
                        next.lineData.add(lineData.get(oldBlock));
                    }

                    for (String text : remainingLines) {
                        newPosition += text.length();
                    }
                }

                modifiedBlock = null;
                oldLine = blockLineOffsets.get(oldBlock) + lineData.get(oldBlock).size();
                oldBlock++;
                oldBlockLine = 0;
            }

            // 3. move any whole blocks we can
            while (oldBlock < lineData.size() && (currentChange == null || getBlockEnd(oldBlock) <= currentChange.getOldPosition())) {
                if (modifiedBlock != null) {
                    if (!modifiedBlock.isEmpty()) {
                        next.lineData.add(modifiedBlock);
                    }
                    modifiedBlock = null;
                }

                newPosition += getBlockEnd(oldBlock) - blockOffsets.get(oldBlock);
                next.lineData.add(lineData.get(oldBlock));
                oldLine = blockLineOffsets.get(oldBlock) + lineData.get(oldBlock).size();
                oldBlock++;
                oldBlockLine = 0;
            }

            // 4. now move any whole lines we can
            while (oldLine < getLineCount() && (currentChange == null || getLineEnd(oldBlock, oldLine) <= currentChange.getOldPosition())) {
                if (modifiedBlock != null && modifiedBlock.size() == MaximumBlockLength) {
                    next.lineData.add(modifiedBlock);
                    modifiedBlock = null;
                }

                if (modifiedBlock == null) {
                    modifiedBlock = new ArrayList<String>();
                }

                modifiedBlock.add(lineData.get(oldBlock).get(oldBlockLine));
                newPosition += modifiedBlock.get(modifiedBlock.size() - 1).length();
                oldLine++;
                oldBlockLine++;
                assert oldBlockLine < lineData.get(oldBlock).size() : "Should have replaced the rest of the block in stop 2 or 3.";
            }

            if (currentChange != null) {
                // 5. move a partial line if necessary
                int oldPosition = getPosition(oldBlock, oldLine, oldColumn);
                if (oldPosition < currentChange.getOldPosition()) {
                    if (modifiedBlock == null) {
                        modifiedBlock = new ArrayList<String>();
                    }

                    int index = modifiedBlock.size() - 1;
                    String text = index >= 0 ? modifiedBlock.get(index) : null;
                    String oldLineText = getLineText(oldBlock, oldLine);
                    String appendText = oldLineText.substring(oldColumn, oldColumn + currentChange.getOldPosition() - oldPosition);
                    if (text == null || text.charAt(text.length() - 1) == '\n') {
                        modifiedBlock.add(appendText);
                    } else {
                        modifiedBlock.set(index, text + appendText);
                    }

                    oldColumn += currentChange.getOldPosition() - oldPosition;
                    newPosition += appendText.length();
                    assert !modifiedBlock.get(modifiedBlock.size() - 1).endsWith("\n") : "Should have replaced the end of the line in step 1 or 4.";
                }

                /*
                 * apply the change
                 */

                assert newPosition == currentChange.getNewPosition() : "Moves are not supported... should be synchronized at this point.";

                int lineStart = 0;
                String newText = currentChange.getNewText();
                for (int j = 0; j <= newText.length(); j++) {
                    if (j == newText.length() || newText.charAt(j) == '\n') {
                        if (modifiedBlock == null) {
                            modifiedBlock = new ArrayList<String>();
                        }

                        int index = modifiedBlock.size() - 1;
                        String text = index >= 0 ? modifiedBlock.get(index) : null;
                        int lineEnd = Math.min(j + 1, newText.length());
                        if (lineStart < lineEnd) {
                            String appendText = newText.substring(lineStart, lineEnd);
                            if (text == null || text.charAt(text.length() - 1) == '\n') {
                                modifiedBlock.add(appendText);
                            } else {
                                modifiedBlock.set(index, text + appendText);
                            }

                            lineStart = lineEnd;
                            if (j == newText.length() - 1) {
                                break;
                            }
                        }
                    }
                }

                // update the current position
                oldPosition = getPosition(oldBlock, oldLine, oldColumn) + currentChange.getOldLength();
                oldBlock = getBlockFromPosition(oldPosition);
                oldLine = getLineNumberFromPosition(oldPosition);
                oldColumn = oldPosition - getLineStart(oldBlock, oldLine);
                oldBlockLine = oldLine - blockLineOffsets.get(oldBlock);
                newPosition += newText.length();
            }
        }

        if (modifiedBlock != null) {
            if (!modifiedBlock.isEmpty()) {
                next.lineData.add(modifiedBlock);
            }
            modifiedBlock = null;
        }

        // now set all the indexes in the new cache
        int newBlockOffset = 0; // offset of the start of the current block
        int newLine = 0; // absolute line number
        for (int newBlock = 0; newBlock < next.lineData.size(); newBlock++) {
            List<String> block = next.lineData.get(newBlock);
            next.blockOffsets.add(newBlockOffset);
            next.blockLineOffsets.add(newLine);
            next.lineOffsets.add(new ArrayList<Integer>());
            int blockLineOffset = 0;
            for (int newBlockLine = 0; newBlockLine < block.size(); newBlockLine++) {
                next.lineOffsets.get(newBlock).add(blockLineOffset);
                blockLineOffset += block.get(newBlockLine).length();
                newLine++;
                // all lines before last end with \n
                assert (newBlock == next.lineData.size() - 1 && newBlockLine == block.size() - 1) || block.get(newBlockLine).endsWith("\n");
                // last line does not end with \n
                assert newBlock < next.lineData.size() - 1 || newBlockLine < block.size() - 1 || !block.get(newBlockLine).endsWith("\n");
            }

            assert next.lineOffsets.get(newBlock).size() == block.size();
            newBlockOffset += blockLineOffset;
        }

        assert next.blockOffsets.size() == next.lineData.size();
        assert next.blockLineOffsets.size() == next.lineData.size();
        assert next.lineOffsets.size() == next.lineData.size();
        assert newBlockOffset == next.length;
        assert newLine == next.lineCount;
//        assert next.getLineCount() == next.blockOffsets.get(next.lineData.size() - 1) + next.lineData.get(next.lineData.size() - 1).size() : "Line counts should match";
//        assert next.getLength() == next.getBlockEnd(next.lineData.size() - 1) : "Lengths should match";

        return next;
    }

    public int getBlockEnd(int block) {
        if (block == lineData.size() - 1) {
            return getLength();
        }

        return blockOffsets.get(block + 1);
    }

    public int getPosition(int block, int line, int column) {
        int blockLine = line - blockLineOffsets.get(block);
        return blockOffsets.get(block) + lineOffsets.get(block).get(blockLine) + column;
    }

    public int getLineStart(int block, int line) {
        int blockLine = line - blockLineOffsets.get(block);
        return blockOffsets.get(block) + lineOffsets.get(block).get(blockLine);
    }

    public int getLineEnd(int block, int line) {
        int blockLine = line - blockLineOffsets.get(block);
        if (blockLine == lineData.get(block).size() - 1) {
            return getBlockEnd(block);
        }

        return blockOffsets.get(block) + lineOffsets.get(block).get(blockLine + 1);
    }

    public String getLineText(int block, int line) {
        int blockLine = line - blockLineOffsets.get(block);
        return lineData.get(block).get(blockLine);
    }

}
