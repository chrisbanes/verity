# App Context

This file describes the target application. Update it with:

- App package/bundle ID
- Screen descriptions and navigation structure
- Key UI patterns (content rows, detail pages, settings)
- Known quirks or accessibility issues

## Example

```
App ID: com.example.launcher
Platform: Android TV

Screens:
- Home: Spotlight carousel at top, content rows below (Movies, TV Shows, etc.)
- Detail: Backdrop image, title, synopsis, tabs (Episodes, Cast, Trailers)
- Settings: Vertical list of options

Navigation:
- D-pad Down from spotlight reaches first content row
- D-pad Right/Left moves between items in a row
- Select on an item opens its detail page
- Back returns to Home
```
