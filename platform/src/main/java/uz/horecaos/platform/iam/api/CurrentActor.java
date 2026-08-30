package uz.horecaos.platform.iam.api;

@FunctionalInterface
public interface CurrentActor {

    AuthenticatedActor get();
}
