package com.copilot;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;
import javax.swing.JFrame;
import javax.swing.JPanel;

/**
 * Full-screen selector over a frozen screenshot. Drag to draw a rectangle, drag inside it to move it,
 * drag its edges/corners to resize it. Enter (or double-click) confirms, Esc cancels,
 * Enter with no rectangle selects the whole screen.
 */
public final class RegionPicker {
    private static final int HANDLE = 9;

    private RegionPicker() {}

    /** Shows the picker; calls back on the EDT with the chosen region in image coordinates, or null if cancelled. */
    public static void pick(BufferedImage shot, Consumer<Rectangle> done) {
        JFrame frame = new JFrame();
        frame.setUndecorated(true);
        frame.setAlwaysOnTop(true);
        frame.setBounds(0, 0, shot.getWidth(), shot.getHeight());
        Canvas canvas = new Canvas(shot, r -> { frame.dispose(); done.accept(r); });
        frame.setContentPane(canvas);
        frame.setVisible(true);
        frame.toFront();
        canvas.requestFocusInWindow();
    }

    private static final class Canvas extends JPanel {
        private final BufferedImage shot;
        private final Consumer<Rectangle> finish;
        private Rectangle sel;
        private Point anchor;      // fixed corner while creating a new rectangle
        private Point grab;        // mouse offset while moving
        private int edge;          // resize bit mask: 1=left 2=right 4=top 8=bottom; 0 = none

        Canvas(BufferedImage shot, Consumer<Rectangle> finish) {
            this.shot = shot;
            this.finish = finish;
            setFocusable(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { press(e.getPoint()); }
                @Override public void mouseDragged(MouseEvent e) { drag(e.getPoint()); }
                @Override public void mouseReleased(MouseEvent e) { grab = null; anchor = null; edge = 0; }
                @Override public void mouseMoved(MouseEvent e) { setCursor(cursorAt(e.getPoint())); }
                @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) confirm(); }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ESCAPE) finish.accept(null);
                    else if (e.getKeyCode() == KeyEvent.VK_ENTER) confirm();
                }
            });
        }

        private void confirm() {
            finish.accept(sel != null && sel.width > 4 && sel.height > 4 ? sel
                : new Rectangle(0, 0, shot.getWidth(), shot.getHeight()));
        }

        private int edgeAt(Point p) {
            if (sel == null) return 0;
            Rectangle o = new Rectangle(sel);
            o.grow(HANDLE, HANDLE);
            if (!o.contains(p)) return 0;
            int e = 0;
            if (Math.abs(p.x - sel.x) <= HANDLE) e |= 1;
            else if (Math.abs(p.x - (sel.x + sel.width)) <= HANDLE) e |= 2;
            if (Math.abs(p.y - sel.y) <= HANDLE) e |= 4;
            else if (Math.abs(p.y - (sel.y + sel.height)) <= HANDLE) e |= 8;
            return e;
        }

        private Cursor cursorAt(Point p) {
            int e = edgeAt(p);
            if (e != 0) {
                int type = switch (e) {
                    case 1 -> Cursor.W_RESIZE_CURSOR; case 2 -> Cursor.E_RESIZE_CURSOR;
                    case 4 -> Cursor.N_RESIZE_CURSOR; case 8 -> Cursor.S_RESIZE_CURSOR;
                    case 5 -> Cursor.NW_RESIZE_CURSOR; case 6 -> Cursor.NE_RESIZE_CURSOR;
                    case 9 -> Cursor.SW_RESIZE_CURSOR; default -> Cursor.SE_RESIZE_CURSOR;
                };
                return Cursor.getPredefinedCursor(type);
            }
            return Cursor.getPredefinedCursor(sel != null && sel.contains(p) ? Cursor.MOVE_CURSOR : Cursor.CROSSHAIR_CURSOR);
        }

        private void press(Point p) {
            edge = edgeAt(p);
            if (edge == 0 && sel != null && sel.contains(p)) grab = new Point(p.x - sel.x, p.y - sel.y);
            else if (edge == 0) { anchor = p; sel = new Rectangle(p); }
            repaint();
        }

        private void drag(Point p) {
            int maxX = shot.getWidth(), maxY = shot.getHeight();
            p = new Point(Math.max(0, Math.min(maxX, p.x)), Math.max(0, Math.min(maxY, p.y)));
            if (anchor != null) {
                sel = new Rectangle(Math.min(anchor.x, p.x), Math.min(anchor.y, p.y),
                    Math.abs(p.x - anchor.x), Math.abs(p.y - anchor.y));
            } else if (grab != null && sel != null) {
                sel.x = Math.max(0, Math.min(maxX - sel.width, p.x - grab.x));
                sel.y = Math.max(0, Math.min(maxY - sel.height, p.y - grab.y));
            } else if (edge != 0 && sel != null) {
                int l = sel.x, r = sel.x + sel.width, t = sel.y, b = sel.y + sel.height;
                if ((edge & 1) != 0) l = p.x;
                if ((edge & 2) != 0) r = p.x;
                if ((edge & 4) != 0) t = p.y;
                if ((edge & 8) != 0) b = p.y;
                sel = new Rectangle(Math.min(l, r), Math.min(t, b), Math.abs(r - l), Math.abs(b - t));
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.drawImage(shot, 0, 0, null);
            g.setColor(new Color(0, 0, 0, 120));
            g.fillRect(0, 0, getWidth(), getHeight());
            if (sel != null && sel.width > 0 && sel.height > 0) {
                g.setClip(sel);
                g.drawImage(shot, 0, 0, null);
                g.setClip(null);
                g.setColor(new Color(0x3B82F6));
                g.setStroke(new BasicStroke(2));
                g.draw(sel);
                for (Point h : handles()) g.fillRect(h.x - HANDLE / 2, h.y - HANDLE / 2, HANDLE, HANDLE);
                label(g, sel.width + " x " + sel.height, sel.x, Math.max(14, sel.y - 6));
            }
            label(g, "Drag to select · drag edges to resize · Enter = capture (no selection = full screen) · Esc = cancel", 16, 24);
            g.dispose();
        }

        private Point[] handles() {
            int x = sel.x, y = sel.y, w = sel.width, h = sel.height;
            return new Point[] {new Point(x, y), new Point(x + w / 2, y), new Point(x + w, y),
                new Point(x, y + h / 2), new Point(x + w, y + h / 2),
                new Point(x, y + h), new Point(x + w / 2, y + h), new Point(x + w, y + h)};
        }

        private void label(Graphics2D g, String text, int x, int y) {
            g.setFont(getFont().deriveFont(Font.BOLD, 13f));
            int w = g.getFontMetrics().stringWidth(text);
            g.setColor(new Color(0, 0, 0, 170));
            g.fillRoundRect(x - 4, y - 14, w + 8, 20, 8, 8);
            g.setColor(Color.WHITE);
            g.drawString(text, x, y);
        }
    }
}
