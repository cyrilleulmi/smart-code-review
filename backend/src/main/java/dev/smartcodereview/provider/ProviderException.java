package dev.smartcodereview.provider;

/** An error talking to the code host; status is mapped to the HTTP status of our API. */
public class ProviderException extends RuntimeException {

    private final int status;

    public ProviderException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
