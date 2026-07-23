import Foundation

/// Removes app-owned reader credentials before an environment crosses into a helper or provider process.
/// Reader secrets belong to the signed CodexBar process and must never become ambient provider configuration.
public enum ChildProcessEnvironment {
    public static let dashboardTokenKey = "CODEXBAR_DASHBOARD_TOKEN"
    public static let readerSecretPrefix = "CODEXBAR_READER_SECRET_"

    public static func sanitized(_ environment: [String: String]) -> [String: String] {
        environment.filter { key, _ in
            key != self.dashboardTokenKey && !key.hasPrefix(self.readerSecretPrefix)
        }
    }
}
