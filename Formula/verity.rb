class Verity < Formula
  desc "LLM-powered E2E testing for mobile and TV"
  homepage "https://github.com/chrisbanes/verity"
  url "https://github.com/chrisbanes/verity/releases/download/v#{version}/verity-#{version}.jar"
  sha256 "PLACEHOLDER"
  license "Apache-2.0"

  depends_on "openjdk@21"

  def install
    libexec.install "verity-#{version}.jar" => "verity.jar"

    (bin/"verity").write <<~BASH
      #!/bin/bash
      export JAVA_HOME="#{Formula["openjdk@21"].opt_prefix}"
      exec "${JAVA_HOME}/bin/java" -jar "#{libexec}/verity.jar" "$@"
    BASH
  end

  test do
    assert_match "verity", shell_output("#{bin}/verity --help")
  end
end
