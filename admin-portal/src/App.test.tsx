import { render, screen } from "@testing-library/react";

import { App } from "./App";

test("identifies the portal as a Phase 0 foundation", () => {
  render(<App />);

  expect(
    screen.getByRole("heading", { name: "Rate Limiter Lab" })
  ).toBeVisible();
  expect(
    screen.getByText(/Administrative workflows begin in Phase 6/)
  ).toBeVisible();
});
