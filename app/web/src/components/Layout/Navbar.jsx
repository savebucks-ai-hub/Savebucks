import React, { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../../lib/api'
import { useAuth } from '../../hooks/useAuth'
import * as DropdownMenu from '@radix-ui/react-dropdown-menu'
import { motion, AnimatePresence } from 'framer-motion'
import {
  Users,
  Settings,
  Bookmark,
  Plus,
  User,
  LogOut,
  Menu,
  X,
  Home,
  Building2,
  Sparkles,
  Gift,
  BarChart3,
  Bell
} from 'lucide-react'
import NotificationBell from '../User/NotificationBell'
import { Avatar } from '../ui/Avatar'
import { Tooltip, TooltipTrigger, TooltipContent, TooltipProvider } from '../ui/Tooltip'
import { Separator } from '../ui/Separator'


const Navbar = () => {
  const { user, signOut } = useAuth()
  const navigate = useNavigate()
  const [isMenuOpen, setIsMenuOpen] = useState(false)

  const { data: userProfile } = useQuery({
    queryKey: ['user-profile', user?.id],
    queryFn: () => api.getUser(user?.user_metadata?.handle || user?.id),
    enabled: !!user?.id,
    staleTime: 5 * 60 * 1000
  })

  const handleSignOut = () => {
    signOut()
    setIsMenuOpen(false)
    navigate('/')
  }

  return (
    <header className="fixed top-0 left-0 right-0 z-50 h-14 lg:h-16">
      {/* Glassmorphism background */}
      <div className="absolute inset-0 backdrop-blur-2xl" style={{ backgroundColor: 'var(--bg-nav)', borderBottom: '1px solid var(--nav-border)' }} />

      <div className="relative h-full max-w-screen-2xl mx-auto px-4 sm:px-6 flex items-center justify-between">

        {/* Logo */}
        <Link to="/" className="flex items-center">
          <motion.img
            src="/logo.png"
            alt="SaveBucks"
            whileHover={{ scale: 1.05 }}
            whileTap={{ scale: 0.95 }}
            className="h-10 w-auto"
            style={{ filter: 'var(--logo-filter)' }}
          />
        </Link>

        {/* Spacer */}
        <div className="flex-1" />

        {/* Right Actions */}
        <div className="flex items-center gap-1">
          <div className="hidden lg:flex items-center">
            <Link
              to="/forums"
              className="flex items-center gap-1.5 px-4 py-2 rounded-full text-sm font-medium transition-all duration-200"
              style={{ color: 'var(--nav-text)' }}
              onMouseEnter={e => e.currentTarget.style.color = 'var(--nav-text-hover)'}
              onMouseLeave={e => e.currentTarget.style.color = 'var(--nav-text)'}
            >
              <Users className="w-4 h-4" />
              <span>Community</span>
            </Link>
            <Link
              to="/saved-items"
              className="flex items-center gap-1.5 px-4 py-2 rounded-full text-sm font-medium transition-all duration-200"
              style={{ color: 'var(--nav-text)' }}
              onMouseEnter={e => e.currentTarget.style.color = 'var(--nav-text-hover)'}
              onMouseLeave={e => e.currentTarget.style.color = 'var(--nav-text)'}
            >
              <Bookmark className="w-4 h-4" />
              <span>Saved</span>
            </Link>
          </div>

          {/* Post Button - Gradient with glow */}
          <motion.div whileHover={{ scale: 1.02 }} whileTap={{ scale: 0.98 }}>
            <Link
              to="/post"
              className="hidden sm:flex items-center gap-1.5 px-5 py-2.5 rounded-full text-sm font-semibold transition-all duration-200"
              style={{ background: 'var(--cta-gradient)', color: 'var(--cta-text)', boxShadow: '0 4px 18px rgba(124,58,237,0.45), 0 1px 4px rgba(219,39,119,0.25)' }}
            >
              <Plus className="w-4 h-4" />
              <span className="hidden lg:inline">Post Deal</span>
            </Link>
          </motion.div>

          {user && <NotificationBell />}

          {user ? (
            <DropdownMenu.Root>
              <DropdownMenu.Trigger asChild>
                <motion.button
                  whileHover={{ scale: 1.05 }}
                  whileTap={{ scale: 0.95 }}
                  className="flex items-center gap-2 p-1 rounded-full hover:bg-slate-900/5 transition-colors"
                >
                  <Avatar
                    className="w-9 h-9 shadow-lg shadow-violet-500/20"
                    gradient="purple"
                    fallback={userProfile?.display_name || user.email || 'U'}
                  />
                </motion.button>
              </DropdownMenu.Trigger>
              <DropdownMenu.Portal>
                <DropdownMenu.Content
                  className="z-50 min-w-[220px] bg-white/90 backdrop-blur-2xl rounded-2xl shadow-2xl p-2 animate-in fade-in-0 zoom-in-95"
                  style={{ boxShadow: '0 8px 32px rgba(124,58,237,0.15), 0 2px 8px rgba(0,0,0,0.06)', border: '1px solid rgba(124,58,237,0.12)' }}
                  sideOffset={8}
                  align="end"
                >
                  <div className="px-3 py-3 mb-1 rounded-xl" style={{ background: 'linear-gradient(135deg, #EDE9FE, #FAF5FF)' }}>
                    <p className="text-sm font-semibold" style={{ color: 'var(--brand-text)' }}>
                      {userProfile?.display_name || userProfile?.handle || 'User'}
                    </p>
                    <p className="text-xs mt-0.5" style={{ color: 'var(--text-tertiary)' }}>
                      {userProfile?.karma ? `${userProfile.karma} karma` : user.email}
                    </p>
                  </div>

                  <DropdownMenu.Item asChild>
                    <Link
                      to={`/user/${userProfile?.handle || user?.id}`}
                      className="flex items-center gap-3 px-3 py-2.5 text-sm rounded-xl outline-none cursor-pointer transition-colors hover:bg-violet-50"
                      style={{ color: 'var(--text-secondary)' }}
                    >
                      <User className="w-4 h-4" style={{ color: 'var(--brand)' }} />
                      Profile
                    </Link>
                  </DropdownMenu.Item>

                  <DropdownMenu.Item asChild>
                    <Link
                      to="/referrals"
                      className="flex items-center gap-3 px-3 py-2.5 text-sm rounded-xl outline-none cursor-pointer transition-colors hover:bg-violet-50"
                      style={{ color: 'var(--text-secondary)' }}
                    >
                      <Gift className="w-4 h-4" style={{ color: 'var(--brand)' }} />
                      Referrals
                    </Link>
                  </DropdownMenu.Item>

                  <DropdownMenu.Item asChild>
                    <Link
                      to="/analytics"
                      className="flex items-center gap-3 px-3 py-2.5 text-sm rounded-xl outline-none cursor-pointer transition-colors hover:bg-violet-50"
                      style={{ color: 'var(--text-secondary)' }}
                    >
                      <BarChart3 className="w-4 h-4" style={{ color: 'var(--brand)' }} />
                      Analytics
                    </Link>
                  </DropdownMenu.Item>

                  <DropdownMenu.Item asChild>
                    <Link
                      to="/notification-settings"
                      className="flex items-center gap-3 px-3 py-2.5 text-sm rounded-xl outline-none cursor-pointer transition-colors hover:bg-violet-50"
                      style={{ color: 'var(--text-secondary)' }}
                    >
                      <Bell className="w-4 h-4" style={{ color: 'var(--brand)' }} />
                      Notifications
                    </Link>
                  </DropdownMenu.Item>

                  {userProfile?.role === 'admin' && (
                    <DropdownMenu.Item asChild>
                      <Link
                        to="/admin"
                        className="flex items-center gap-3 px-3 py-2.5 text-sm rounded-xl outline-none cursor-pointer transition-colors hover:bg-violet-50"
                        style={{ color: 'var(--text-secondary)' }}
                      >
                        <Settings className="w-4 h-4" style={{ color: 'var(--brand)' }} />
                        Admin
                      </Link>
                    </DropdownMenu.Item>
                  )}

                  <div className="h-px my-1.5" style={{ background: 'rgba(124,58,237,0.1)' }} />

                  <DropdownMenu.Item
                    onClick={handleSignOut}
                    className="flex items-center gap-3 px-3 py-2.5 text-sm text-red-600 hover:bg-red-500/10 rounded-xl outline-none cursor-pointer transition-colors"
                  >
                    <LogOut className="w-4 h-4" />
                    Sign Out
                  </DropdownMenu.Item>
                </DropdownMenu.Content>
              </DropdownMenu.Portal>
            </DropdownMenu.Root>
          ) : (
            <motion.div whileHover={{ scale: 1.02 }} whileTap={{ scale: 0.98 }}>
              <Link
                to="/signin"
                className="flex items-center gap-1.5 px-4 py-2 text-sm font-medium text-slate-700 hover:text-slate-900 hover:bg-slate-900/5 rounded-full transition-all duration-200"
              >
                <User className="w-4 h-4" />
                <span className="hidden sm:inline">Sign In</span>
              </Link>
            </motion.div>
          )}

          <button
            onClick={() => setIsMenuOpen(!isMenuOpen)}
            className="lg:hidden p-2.5 rounded-full text-slate-600 hover:text-slate-900 hover:bg-slate-900/5 transition-colors"
          >
            {isMenuOpen ? <X className="w-5 h-5" /> : <Menu className="w-5 h-5" />}
          </button>
        </div>
      </div>

      <AnimatePresence>
        {isMenuOpen && (
          <motion.div
            initial={{ opacity: 0, y: -10 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -10 }}
            className="lg:hidden absolute top-full left-0 right-0 bg-white/80 backdrop-blur-2xl shadow-2xl shadow-slate-900/5"
          >
            <nav className="p-3 space-y-1">
              <Link to="/" onClick={() => setIsMenuOpen(false)} className="flex items-center gap-3 px-4 py-3 rounded-xl text-slate-700 hover:bg-slate-900/5 transition-colors">
                <Home className="w-5 h-5" /> Home
              </Link>
              <Link to="/forums" onClick={() => setIsMenuOpen(false)} className="flex items-center gap-3 px-4 py-3 rounded-xl text-slate-700 hover:bg-slate-900/5 transition-colors">
                <Users className="w-5 h-5" /> Community
              </Link>
              <Link to="/companies" onClick={() => setIsMenuOpen(false)} className="flex items-center gap-3 px-4 py-3 rounded-xl text-slate-700 hover:bg-slate-900/5 transition-colors">
                <Building2 className="w-5 h-5" /> Companies
              </Link>
              <Link to="/saved-items" onClick={() => setIsMenuOpen(false)} className="flex items-center gap-3 px-4 py-3 rounded-xl text-slate-700 hover:bg-slate-900/5 transition-colors">
                <Bookmark className="w-5 h-5" /> Saved
              </Link>
              <Link to="/post" onClick={() => setIsMenuOpen(false)} className="flex items-center gap-3 px-4 py-3 mt-2 bg-gradient-to-r from-violet-500 to-purple-600 text-white rounded-xl font-semibold shadow-lg shadow-violet-500/25">
                <Plus className="w-5 h-5" /> Post Deal
              </Link>
            </nav>
          </motion.div>
        )}
      </AnimatePresence>
    </header>
  )
}

export default Navbar
