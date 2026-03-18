# CI Android Smoke Tests — Design

## Goal

Run Android smoke tests on every PR in parallel with the existing build job, using a GitHub-hosted Ubuntu runner with KVM-accelerated emulator.

## Workflow changes

Add a `smoke-android` job to `.github/workflows/ci.yml` alongside the existing `build` job. No dependency between them — they run in parallel.

## Job: `smoke-android`

**Runner:** `ubuntu-latest`

**Steps:**

1. Checkout
2. Setup Java 21 (Temurin)
3. Setup Gradle (`gradle/actions/setup-gradle@v4`)
4. Enable KVM access (udev rule for `/dev/kvm`)
5. Run `reactivecircus/android-emulator-runner@v2`:
   - API 34, `default` system image, `x86_64` arch
   - Animations disabled by the action
   - Script: `./gradlew :verity:smoke-tests:smokeTest -Dinclude.tags=android`

## Emulator config

- API 34 chosen for stability and availability on GitHub runners
- `default` target (no Google APIs needed — tests target Settings app)
- `x86_64` for KVM hardware acceleration
- Action handles AVD creation, boot wait, and teardown

## KVM access

GitHub Ubuntu runners have KVM available but need a udev rule:

```yaml
- name: Enable KVM
  run: |
    echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
    sudo udevadm control --reload-rules
    sudo udevadm trigger --name-match=kvm
```
