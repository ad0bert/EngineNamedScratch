# Release notes

One Markdown file per release, named after its tag: `v0.8.0.md`. The release workflow
(`.github/workflows/release.yml`) uses it for the GitHub release:

- the first line, a `# ` heading, is the release title (`# v0.8.0: What changed`);
- everything after it is the release description.

Write the file before pushing the tag. Without one, the release is titled with the tag and described with that
version's section of `builds/releaseHistory.txt`.

To publish a release again for an existing tag (after fixing its notes, or when a run failed), run the workflow by
hand: Actions → release → Run workflow, with the tag. It updates the release's title, notes and files, and skips the
upload to GitHub Packages when that version is already there (a published version cannot be replaced).
