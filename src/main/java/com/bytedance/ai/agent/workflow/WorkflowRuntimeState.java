package com.bytedance.ai.agent.workflow;

import com.bytedance.ai.agent.api.CompareMatrixView;
import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.api.Slot;
import com.bytedance.ai.agent.api.SpuCardView;
import com.bytedance.ai.agent.api.ToolCallView;
import com.bytedance.ai.agent.memory.ConversationMemory;
import com.bytedance.ai.agent.memory.ConversationSummary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class WorkflowRuntimeState {

    private WorkflowStatus status = WorkflowStatus.RUNNING;
    private IntentType intent = IntentType.RECOMMEND_VAGUE;
    private Slot slots = Slot.empty();
    private String targetNode;
    private String currentNode;
    private String userId;
    private String action;
    private boolean needsReset;
    private ConversationMemory memory = ConversationMemory.empty();
    private ConversationSummary memorySummary = ConversationSummary.empty();
    private final List<ToolCallView> toolCalls = new ArrayList<>();
    private final Map<String, Object> lastToolResults = new LinkedHashMap<>();
    private List<SpuCardView> cards = new ArrayList<>();
    private CompareMatrixView compareMatrix;
    private String answerText = "";
    private final AtomicBoolean generatedByModel = new AtomicBoolean(false);
    private PendingSelection pendingSelection;
    private PendingConfirmation pendingConfirmation;
    private PendingSlot pendingSlot;

    public WorkflowStatus status() {
        return status;
    }

    public void status(WorkflowStatus status) {
        this.status = status == null ? WorkflowStatus.RUNNING : status;
    }

    public IntentType intent() {
        return intent;
    }

    public void intent(IntentType intent) {
        this.intent = intent == null ? IntentType.RECOMMEND_VAGUE : intent;
    }

    public Slot slots() {
        return slots;
    }

    public void slots(Slot slots) {
        this.slots = slots == null ? Slot.empty() : slots;
    }

    public String targetNode() {
        return targetNode;
    }

    public void targetNode(String targetNode) {
        this.targetNode = targetNode;
    }

    public String currentNode() {
        return currentNode;
    }

    public void currentNode(String currentNode) {
        this.currentNode = currentNode;
    }

    public String userId() {
        return userId;
    }

    public void userId(String userId) {
        this.userId = userId;
    }

    public String action() {
        return action;
    }

    public void action(String action) {
        this.action = action;
    }

    public boolean needsReset() {
        return needsReset;
    }

    public void needsReset(boolean needsReset) {
        this.needsReset = needsReset;
    }

    public ConversationMemory memory() {
        return memory;
    }

    public void memory(ConversationMemory memory) {
        this.memory = memory == null ? ConversationMemory.empty() : memory;
    }

    public ConversationSummary memorySummary() {
        return memorySummary;
    }

    public void memorySummary(ConversationSummary memorySummary) {
        this.memorySummary = memorySummary == null ? ConversationSummary.empty() : memorySummary;
    }

    public List<ToolCallView> toolCalls() {
        return toolCalls;
    }

    public Map<String, Object> lastToolResults() {
        return lastToolResults;
    }

    public List<SpuCardView> cards() {
        return cards;
    }

    public void cards(List<SpuCardView> cards) {
        this.cards = cards == null ? new ArrayList<>() : new ArrayList<>(cards);
    }

    public CompareMatrixView compareMatrix() {
        return compareMatrix;
    }

    public void compareMatrix(CompareMatrixView compareMatrix) {
        this.compareMatrix = compareMatrix;
    }

    public String answerText() {
        return answerText;
    }

    public void answerText(String answerText) {
        this.answerText = answerText == null ? "" : answerText;
    }

    public AtomicBoolean generatedByModel() {
        return generatedByModel;
    }

    public PendingSelection pendingSelection() {
        return pendingSelection;
    }

    public void pendingSelection(PendingSelection pendingSelection) {
        this.pendingSelection = pendingSelection;
    }

    public PendingConfirmation pendingConfirmation() {
        return pendingConfirmation;
    }

    public void pendingConfirmation(PendingConfirmation pendingConfirmation) {
        this.pendingConfirmation = pendingConfirmation;
    }

    public PendingSlot pendingSlot() {
        return pendingSlot;
    }

    public void pendingSlot(PendingSlot pendingSlot) {
        this.pendingSlot = pendingSlot;
    }

    public boolean isPaused() {
        return status == WorkflowStatus.WAITING_CONFIRMATION
                || status == WorkflowStatus.WAITING_SELECTION
                || status == WorkflowStatus.WAITING_SLOT;
    }

    public void clearPending() {
        this.pendingSelection = null;
        this.pendingConfirmation = null;
        this.pendingSlot = null;
    }

    public void resetForNewIntent() {
        slots = Slot.empty();
        targetNode = null;
        currentNode = null;
        action = null;
        needsReset = false;
        toolCalls.clear();
        lastToolResults.clear();
        cards = new ArrayList<>();
        compareMatrix = null;
        answerText = "";
        pendingSelection = null;
        pendingConfirmation = null;
        pendingSlot = null;
        status = WorkflowStatus.RUNNING;
    }
}
