package uz.qoida.platform.iam.api;

@FunctionalInterface
public interface CurrentActor {

    AuthenticatedActor get();
}
