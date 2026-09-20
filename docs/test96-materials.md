# test.96: material refinement

This change retains the test.95 navigation, two-column strategy groups and in-place expansion. It replaces rounded content-card fills with a shared gradient, directional light rim and restrained ambient shadows, and gives the More menu a focusable anchored frosted popover. Blur uses background capture rather than blurring foreground labels; disabling blur retains the gradient and legible controls.

Flow, memory and CPU numbers use sans-serif tabular figures. IP and latency retain explicit monospacing. Untested latency has a transparent `— ms` placeholder. The transparent configuration selector and three equally sized home actions remain in place.

Configured group images keep their colors and cache. Image fetching uses the existing loopback Mihomo entry while Root runtime is running, without starting or restarting it and without a direct fallback during that request. Unconfigured groups show an explicit monogram rather than pretending a generic Tune icon is a downloaded brand logo.

Runtime-boundary hashes and interaction/render tests remain release gates. Automated rasterization does not prove OEM GPU blur behavior, remote image availability or long-running Root stability on physical devices. Those remain real-device validation items.
