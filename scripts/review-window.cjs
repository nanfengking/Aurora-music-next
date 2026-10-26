// Test-only: schedule native window changes on Node's event loop, not directly
// on the inspector's evaluation stack. No retries or swallowed failures.
async function resizeReviewWindow(application, page, width, height) {
  await application.evaluate(({ BrowserWindow }, dimensions) => new Promise((resolve, reject) => {
    setImmediate(() => {
      try {
        BrowserWindow.getAllWindows()[0].setContentSize(...dimensions)
        resolve(true)
      } catch (error) { reject(error) }
    })
  }), [width, height])
  // Windows scaling can round one physical pixel. Assert the actual viewport;
  // otherwise a lost resize reply could silently leave layout coverage false.
  await page.waitForFunction(([width, height]) =>
    Math.abs(innerWidth - width) <= 1 && Math.abs(innerHeight - height) <= 1,
  [width, height], { timeout: 5000 })
}
module.exports = { resizeReviewWindow }
