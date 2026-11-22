package neuralcloud.controlplane.worker;

public record DialogueMessage(Speaker speaker, String text) {
    public enum Speaker { USER, MISTY }
}
