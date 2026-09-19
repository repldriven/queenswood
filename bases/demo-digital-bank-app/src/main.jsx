import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./styles.css";
import { brand } from "./brand.js";
import App from "./App.jsx";

document.title = brand.name;

createRoot(document.getElementById("app")).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
