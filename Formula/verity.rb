class Verity < Formula
  desc "LLM-powered E2E testing for mobile and TV"
  homepage "https://github.com/chrisbanes/verity"
  version "VERSION_PLACEHOLDER"
  url "https://github.com/chrisbanes/verity/releases/download/v#{version}/verity-#{version}.jar", using: :nounzip
  sha256 "UNIVERSAL_SHA_PLACEHOLDER"
  on_macos do
    on_arm do
      url "https://github.com/chrisbanes/verity/releases/download/v#{version}/verity-#{version}-macos-aarch64.jar", using: :nounzip
      sha256 "MACOS_SHA_PLACEHOLDER"
    end
  end

  on_linux do
    on_intel do
      url "https://github.com/chrisbanes/verity/releases/download/v#{version}/verity-#{version}-linux-x86_64.jar", using: :nounzip
      sha256 "LINUX_SHA_PLACEHOLDER"
    end
  end

  license "Apache-2.0"

  depends_on "openjdk@21"

  def install
    suffix = if OS.mac? && Hardware::CPU.arm?
      "-macos-aarch64"
    elsif OS.linux? && Hardware::CPU.intel?
      "-linux-x86_64"
    else
      ""
    end
    libexec.install "verity-#{version}#{suffix}.jar" => "verity.jar"

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
